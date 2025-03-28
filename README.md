# WinRM Java Client

![GitHub release (with filter)](https://img.shields.io/github/v/release/metricshub/winrm-java)
![Build](https://img.shields.io/github/actions/workflow/status/metricshub/winrm-java/deploy.yml)
![GitHub top language](https://img.shields.io/github/languages/top/metricshub/winrm-java)
![License](https://img.shields.io/github/license/metricshub/winrm-java)

This project uses [WS-Man Client](https://github.com/OpenNMS/wsman) and [winrm4j](https://github.com/cloudsoft/winrm4j/)

See **[Project Documentation](https://metricshub.org/winrm-java)** and the [Javadoc](https://metricshub.org/winrm-java/apidocs) for more information on how to use this library in your code.

The Windows Remote Management (WinRM) Java Client is a library that enables to:
* Connect to a remote Windows server using one of the two authentication types (NTLM, KERBEROS)
* Execute WMI Query Language (WQL) queries which uses HTTP/HTTPS protocols.

## Build instructions

This is a simple Maven project. Build with:

```bash
mvn verify
```

## Release instructions

The artifact is deployed to Sonatype's [Maven Central](https://central.sonatype.com/).

The actual repository URL is https://s01.oss.sonatype.org/, with server Id `ossrh` and requires credentials to deploy
artifacts manually.

But it is strongly recommended to only use [GitHub Actions "Release to Maven Central"](actions/workflows/release.yml) to perform a release:

* Manually trigger the "Release" workflow
* Specify the version being released and the next version number (SNAPSHOT)
* Release the corresponding staging repository on [Sonatype's Nexus server](https://s01.oss.sonatype.org/)
* Merge the PR that has been created to prepare the next version

## License

License is Apache-2. Each source file must include the Apache-2 header (build will fail otherwise).
To update source files with the proper header, simply execute the below command:

```bash
mvn license:update-file-header
```
