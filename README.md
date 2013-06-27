Discard Old Build plugin
===========================

Jenkins plugin to discard old builds with detailed configuration.

What's this?
-------------

Discard Old Build is a [Jenkins](http://jenkins-ci.org/) plugin.
This plugin provides a post-build step where you can discard old build results.

* You can configures in more detail than the default 'Discard Old Build' function.
* Other than # of builds and days, you can specify build status to discard.
* For older builds, you can configure interval to keep builds (once in a month, once in ten builds...).
* You can also delete builds which has logfile size smaller or larger than specified bytes
* Or use Regular expression to parse log file for builds to discard
*
* Notes:
* Multi discard conditions can be selected and right now they are executed in order showing in UI
* Builds in Last are always kept


TODO
----
* Add help files
* Dynamic add discard condition in UI which decides the discard priority


