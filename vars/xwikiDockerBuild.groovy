#!/usr/bin/env groovy

/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

void call(body)
{
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    echoXWiki "Configurations to execute: ${config.configurations}"

    // If no modules are passed, then find all modules containing docker tests.
    def modules = config.modules ?: getDockerModules()

    echoXWiki "Modules to execute: ${modules}"

    // Mark build as a Docker build in the Jenkins UI to differentiate it from others "standard" builds
    def badgeText = 'Docker Build'
    manager.addInfoBadge(badgeText)
    manager.createSummary('green.gif').appendText("<h1>${badgeText}</h1>", false, false, false, 'green')
    currentBuild.rawBuild.save()

    // Run docker tests on all modules for all supported configurations
    config.configurations.eachWithIndex() { testConfig, i ->
        echoXWiki "Processing configuration: ${testConfig}"
        def systemProperties = []
        // Note: don't use each() since it leads to unserializable exceptions
        for (def entry in testConfig.value) {
            systemProperties.add("-Dxwiki.test.ui.${entry.key}=${entry.value}")
        }
        def testConfigurationName = getTestConfigurationName(testConfig.value)
        // Only execute maven with -U for the first Maven builds since XWiki SNAPSHOT dependencies don't change with
        // configurations.
        // Only clean for the first execution since we don't need to clean more.
        def flags = '-e'
        if (i == 0) {
            flags = "${flags} -U"
        }
        modules.each() { modulePath ->
            echoXWiki "Processing module: ${modulePath}"
            def moduleName = modulePath.substring(modulePath.lastIndexOf('/') + 1, modulePath.length())
            echoXWiki "Module name: ${moduleName}"
            def profiles = 'docker,legacy,integration-tests,snapshotModules'
            def commonProperties =
                '-Dxwiki.checkstyle.skip=true -Dxwiki.surefire.captureconsole.skip=true -Dxwiki.revapi.skip=true'
            // On the first execution inside this module, build the ui module to be sure we get fresh artifacts
            // downloaded in the local repository. This is needed because when XWikiDockerExtension executes we
            // only resolve from the local repository to speed up the test execution.
            // Note 1: we don't need to build the POM module since it's the parent of the -ui and docker submodules.
            // Note 2: we also don't need to build the pageobjects modules since it's built by the standard platform
            // jobs (i.e. built by -Pintegration-tests).
            if (i == 0) {
                def uiModuleName = "${modulePath}/${moduleName}-ui"
                echoXWiki "UI module pom: ${uiModuleName}/pom.xml"
                def exists = fileExists "${uiModuleName}/pom.xml"
                if (exists) {
                    echoXWiki "Building UI module: ${uiModuleName}"
                    build(
                        name: "UI module for ${moduleName}",
                        profiles: profiles,
                        properties: commonProperties,
                        mavenFlags: "--projects ${uiModuleName} ${flags}",
                        skipCheckout: true,
                        xvnc: false,
                        goals: 'clean install',
                        skipMail: true,
                        jobProperties: config.jobProperties
                    )
                }
            }
            // Then run the tests
            // Note: We clean every time since we set the maven.build.dir and specify a directory that depends on the
            // configuration (each config has its own target dir).
            def testModuleName = "${modulePath}/${moduleName}-test/${moduleName}-test-docker"
            echoXWiki "Building Test module: ${testModuleName}"
            build(
                name: "${testConfig.key} - Docker tests for ${moduleName}",
                profiles: profiles,
                properties:
                  "${commonProperties} -Dmaven.build.dir=target/${testConfigurationName} ${systemProperties.join(' ')}",
                mavenFlags: "--projects ${testModuleName} ${flags}",
                skipCheckout: true,
                xvnc: false,
                goals: 'clean verify',
                skipMail: config.skipMail,
                jobProperties: config.jobProperties
            )
        }
    }
}

/**
 * Find all modules named -test-docker to located docker-based tests.
 */
private def getDockerModules()
{
    def modules = []
    def dockerModuleFiles = findFiles(glob: '**/*-test-docker/pom.xml')
    // Note: don't use each() since it leads to unserializable exceptions
    for (def it in dockerModuleFiles) {
        // Skip 'xwiki-platform-test-docker' since it matches the glob pattern but isn't a test module.
        if (!it.path.contains('xwiki-platform-test-docker')) {
            // Find grand parent module, e.g. return the path to xwiki-platform-menu when
            // xwiki-platform-menu-test-docker is found.
            modules.add(getParentPath(getParentPath(getParentPath(it.path))))
        }
    }
    return modules
}

private def getParentPath(def path)
{
    return path.substring(0, path.lastIndexOf('/'))
}

private def getTestConfigurationName(def testConfig)
{
    def databasePart =
        "${testConfig.database}-${testConfig.databaseTag ?: 'default'}-${testConfig.jdbcVersion ?: 'default'}"
    def servletEnginePart = "${testConfig.servletEngine}-${testConfig.servletEngineTag ?: 'default'}"
    def browserPart = "${testConfig.browser}"
    return "${databasePart}-${servletEnginePart}-${browserPart}"
}

private void build(map)
{
    xwikiBuild(map.name) {
        mavenOpts = map.mavenOpts ?: "-Xmx2048m -Xms512m"
        if (map.goals != null) {
            goals = map.goals
        }
        if (map.profiles != null) {
            profiles = map.profiles
        }
        if (map.properties != null) {
            properties = map.properties
        }
        if (map.pom != null) {
            pom = map.pom
        }
        if (map.mavenFlags != null) {
            mavenFlags = map.mavenFlags
        }
        if (map.skipCheckout != null) {
            skipCheckout = map.skipCheckout
        }
        if (map.xvnc != null) {
            xvnc = map.xvnc
        }
        if (map.skipMail != null) {
            skipMail = map.skipMail
        }
        if (map.jobProperties != null) {
            jobProperties = map.jobProperties
        }
    }
}
