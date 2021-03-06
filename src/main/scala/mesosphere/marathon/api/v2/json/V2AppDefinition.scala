package mesosphere.marathon.api.v2.json

import java.lang.{ Double => JDouble, Integer => JInt }

import com.wix.accord._
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.AppDefinition.VersionInfo.FullVersionInfo
import mesosphere.marathon.state._
import org.apache.mesos.{ Protos => mesos }

import scala.collection.immutable.Seq
import scala.concurrent.duration._

import com.wix.accord.dsl._

case class V2AppDefinition(

    id: PathId = AppDefinition.DefaultId,

    cmd: Option[String] = AppDefinition.DefaultCmd,

    args: Option[Seq[String]] = AppDefinition.DefaultArgs,

    user: Option[String] = AppDefinition.DefaultUser,

    env: Map[String, String] = AppDefinition.DefaultEnv,

    instances: JInt = AppDefinition.DefaultInstances,

    cpus: JDouble = AppDefinition.DefaultCpus,

    mem: JDouble = AppDefinition.DefaultMem,

    disk: JDouble = AppDefinition.DefaultDisk,

    executor: String = AppDefinition.DefaultExecutor,

    constraints: Set[Constraint] = AppDefinition.DefaultConstraints,

    uris: Seq[String] = AppDefinition.DefaultUris,

    storeUrls: Seq[String] = AppDefinition.DefaultStoreUrls,

    ports: Seq[JInt] = AppDefinition.DefaultPorts,

    requirePorts: Boolean = AppDefinition.DefaultRequirePorts,

    backoff: FiniteDuration = AppDefinition.DefaultBackoff,

    backoffFactor: JDouble = AppDefinition.DefaultBackoffFactor,

    maxLaunchDelay: FiniteDuration = AppDefinition.DefaultMaxLaunchDelay,

    container: Option[Container] = AppDefinition.DefaultContainer,

    healthChecks: Set[HealthCheck] = AppDefinition.DefaultHealthChecks,

    dependencies: Set[PathId] = AppDefinition.DefaultDependencies,

    upgradeStrategy: UpgradeStrategy = AppDefinition.DefaultUpgradeStrategy,

    labels: Map[String, String] = AppDefinition.DefaultLabels,

    acceptedResourceRoles: Option[Set[String]] = None,

    ipAddress: Option[IpAddress] = None,

    version: Timestamp = Timestamp.now(),

    versionInfo: Option[V2AppDefinition.VersionInfo] = None) extends Timestamped {

  /**
    * Returns true if all health check port index values are in the range
    * of ths app's ports array, or if defined, the array of container
    * port mappings.
    */
  def portIndicesAreValid(): Boolean =
    this.toAppDefinition.portIndicesAreValid()

  /**
    * Returns the canonical internal representation of this API-specific
    * application definition.
    */
  def toAppDefinition: AppDefinition = {
    val appVersionInfo = versionInfo match {
      case Some(V2AppDefinition.VersionInfo(lastScalingAt, lastChangeAt)) =>
        AppDefinition.VersionInfo.FullVersionInfo(version, lastScalingAt, lastChangeAt)
      case None =>
        AppDefinition.VersionInfo.OnlyVersion(version)
    }
    AppDefinition(
      id = id, cmd = cmd, args = args, user = user, env = env, instances = instances, cpus = cpus,
      mem = mem, disk = disk, executor = executor, constraints = constraints, uris = uris,
      storeUrls = storeUrls, ports = ports, requirePorts = requirePorts, backoff = backoff,
      backoffFactor = backoffFactor, maxLaunchDelay = maxLaunchDelay, container = container,
      healthChecks = healthChecks, dependencies = dependencies, upgradeStrategy = upgradeStrategy,
      labels = labels, acceptedResourceRoles = acceptedResourceRoles,
      ipAddress = ipAddress, versionInfo = appVersionInfo
    )
  }

  def withCanonizedIds(base: PathId = PathId.empty): V2AppDefinition = {
    val baseId = id.canonicalPath(base)
    copy(id = baseId, dependencies = dependencies.map(_.canonicalPath(baseId)))
  }
}

object V2AppDefinition {

  case class VersionInfo(
    lastScalingAt: Timestamp,
    lastConfigChangeAt: Timestamp)

  def apply(app: AppDefinition): V2AppDefinition = {
    val maybeVersionInfo = app.versionInfo match {
      case FullVersionInfo(_, lastScalingAt, lastConfigChangeAt) => Some(VersionInfo(lastScalingAt, lastConfigChangeAt))
      case _ => None
    }

    V2AppDefinition(
      id = app.id, cmd = app.cmd, args = app.args, user = app.user, env = app.env, instances = app.instances,
      cpus = app.cpus, mem = app.mem, disk = app.disk, executor = app.executor, constraints = app.constraints,
      uris = app.uris, storeUrls = app.storeUrls, ports = app.ports, requirePorts = app.requirePorts,
      backoff = app.backoff, backoffFactor = app.backoffFactor, maxLaunchDelay = app.maxLaunchDelay,
      container = app.container, healthChecks = app.healthChecks, dependencies = app.dependencies,
      upgradeStrategy = app.upgradeStrategy, labels = app.labels, acceptedResourceRoles = app.acceptedResourceRoles,
      ipAddress = app.ipAddress, version = app.version, versionInfo = maybeVersionInfo)
  }

  /**
    * We cannot validate HealthChecks here, because it would break backwards compatibility in weird ways.
    * If users had already one invalid app definition, each deployment would cause a complete revalidation of
    * the root group including the invalid one.
    * Until the user changed all invalid apps, the user would get weird validation
    * errors for every deployment potentially unrelated to the deployed apps.
    */
  implicit val appDefinitionValidator = validator[V2AppDefinition] { appDef =>
    appDef.id is valid
    appDef.dependencies is valid
    appDef.upgradeStrategy is valid
    appDef.storeUrls is every(urlCanBeResolvedValidator)
    appDef.ports is elementsAreUnique(filterOutRandomPorts)
    appDef.executor should matchRegexFully("^(//cmd)|(/?[^/]+(/[^/]+)*)|$")
    appDef is containsCmdArgsContainerValidator
    appDef is portIndicesAreValid
    appDef.instances.intValue should be >= 0
  }

  def filterOutRandomPorts(ports: scala.Seq[JInt]): scala.Seq[JInt] = {
    ports.filterNot(_ == AppDefinition.RandomPortValue)
  }

  private def containsCmdArgsContainerValidator: Validator[V2AppDefinition] = {
    new Validator[V2AppDefinition] {
      override def apply(app: V2AppDefinition): Result = {
        val cmd = app.cmd.nonEmpty
        val args = app.args.nonEmpty
        val container = app.container.exists(_ != Container.Empty)
        if ((cmd ^ args) || (!(cmd || args) && container)) Success
        else Failure(Set(RuleViolation(app,
          "AppDefinition must either contain one of 'cmd' or 'args', and/or a 'container'.", None)))
      }
    }
  }

  private def portIndicesAreValid: Validator[V2AppDefinition] = {
    new Validator[V2AppDefinition] {
      override def apply(app: V2AppDefinition): Result = {
        val appDef = app.toAppDefinition
        val validPortIndices = appDef.hostPorts.indices

        if (appDef.healthChecks.forall { hc =>
          hc.protocol == Protocol.COMMAND || (hc.portIndex match {
            case Some(idx) => validPortIndices contains idx
            case None      => validPortIndices.length == 1 && validPortIndices.head == 0
          })
        }) Success
        else Failure(Set(RuleViolation(app,
          "Health check port indices must address an element of the ports array or container port mappings.", None)))
      }
    }
  }
}
