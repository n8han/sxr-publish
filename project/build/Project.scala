import sbt._

class Project(info: ProjectInfo) extends PluginProject(info) with sxr.Publish with posterous.Publish {
  val dispatch = "net.databinder" %% "dispatch-http" % "0.7.3"

  override def extraTags = "sbt plugin" :: Nil

  override def managedStyle = ManagedStyle.Maven
  val publishTo = "Scala Tools Nexus" at "http://nexus.scala-tools.org/content/repositories/releases/"
  Credentials(Path.userHome / ".ivy2" / ".credentials", log)
}
