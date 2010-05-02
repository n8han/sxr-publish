import sbt._

class Project(info: ProjectInfo) extends PluginProject(info) {
  val dispatch = "net.databinder" %% "dispatch-http" % "0.7.3"

  override def managedStyle = ManagedStyle.Maven
  val publishTo = "Scala Tools Nexus" at "http://nexus.scala-tools.org/content/repositories/releases/"
  Credentials(Path.userHome / ".ivy2" / ".credentials", log)
}
