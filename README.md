Sxr Publish
===========

This [Simple Build Tool][sbt] plugin publishes annotated source files produced by the [Scala X-Ray][sxr] compiler plugin. It communicates with an  [Sxr Sourced][sxr-sourced] server using a simple shared secret protocol over HTTP: an HmacSHA1 signature of publish paths and file contents is attached to PUT requests in a `sig` parameter.

[sbt]: http://code.google.com/p/simple-build-tool/
[sxr]: http://github.com/harrah/browse
[sxr-sourced]: http://github.com/softprops/sxr-sourced

Setup
-----

The plugin and server are designed to work as a service hosted at `sourced.implicit.ly`. The shared secret is bound to the Ivy "organization", Maven "groupId" of a project (defined by [`sxrOrg`][sxrOrg]). A secret is assigned to an organization, allowing projects that belong to it to publish to versioned paths below its dotted identifier. For example, knowing the secret for "com.example", you could publish to the following path:

[sxrOrg]: http://sourced.implicit.ly/net.databinder/sxr-publish/0.1.2/sxr.scala.html#16936

    /com.example/project/0.0.1/SourceName.scala.html

Shared secrets are retrieved in a file in the user's home directory. If you are publishing to `sourced.implicit.ly` (defined by [`sxrHostname`][sxrHostname]), the default [`sxrCredentialsPath`][sxrCredentialsPath] is `~/.sourced.implicit.ly` . Within this file you may define as many organization secrets as you know. The secret "secret" for `com.example` is defined as follows:

[sxrHostname]: http://sourced.implicit.ly/net.databinder/sxr-publish/0.1.2/sxr.scala.html#16945
[sxrCredentialsPath]: http://sourced.implicit.ly/net.databinder/sxr-publish/0.1.2/sxr.scala.html#16947

    com.example=secret

The plugin itself is published to scala-tools, so that you may use it with any sbt project by specifying


    val sxr_publish = "net.databinder" % "sxr-publish" % latest_version

in your `Plugins.scala`, and mixing the trait `sxr.Publish` into your project definition.

Actions
-------

Before publishing your sources, you may preview them locally with the `preview-sxr` task. If everything is in order, publish with `publish-sxr`.

Administration
--------------

Contact n8han or softprops through github or email to request a secret for one or more organizations. If we aren't going to know who you are immediately, please give us some way to verify that you are indeed responsible for the organization name.