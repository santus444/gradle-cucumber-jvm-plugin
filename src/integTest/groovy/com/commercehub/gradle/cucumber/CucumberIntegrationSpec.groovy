package com.commercehub.gradle.cucumber

import groovy.util.logging.Slf4j
import nebula.test.IntegrationSpec
import nebula.test.functional.ExecutionResult
import org.gradle.api.GradleException

/**
 * Created by jgelais on 11/19/15.
 */
@SuppressWarnings('DuplicateStringLiteral')
@Slf4j
class CucumberIntegrationSpec extends IntegrationSpec {
    def setup() {
        copyResources('teststeps/TestSteps.groovy', 'src/test/groovy/cucumber/steps/TestSteps.groovy')
        buildFile << '''
            apply plugin: 'groovy'
            apply plugin: 'com.commercehub.cucumber-jvm'

            addCucumberSuite 'test'

            repositories {
                jcenter()
            }

            dependencies {
                compile 'org.codehaus.groovy:groovy-all:2.4.1'
                compile 'info.cukes:cucumber-java:1.2.2'
            }

            test {
                systemProperty 'foo', 'bar'
            }
        '''.stripIndent()
    }

    def testHappyPath() {
        given:
        copyResources('testfeatures/happypath.feature', 'src/test/resources/features/happypath.feature')

        when:
        ExecutionResult result = runTasksSuccessfully('test')
        log.info(result.standardOutput)

        then:
        !result.wasUpToDate(':compileTestGroovy')
        result.wasExecuted(':test')
        new File(projectDir, 'build/reports/test').list().flatten()*.replace('\\', '/')
            .findResults { it.matches('report-feature_features-happypath-feature?\\.html') ? it : null } != null
    }

    def testFailingBackgroundStep() {
        given:
        copyResources('testfeatures/failing-background-test.feature',
                'src/test/resources/features/failing-background-test.feature')

        when:
        ExecutionResult result = runTasksSuccessfully('test')
        log.info(result.standardOutput)

        then:
        thrown GradleException
    }

    def testSysProps() {
        given:
        copyResources('testfeatures/check-sysprop.feature', 'src/test/resources/features/check-sysprop.feature')

        when:
        ExecutionResult result = runTasksSuccessfully('test')
        log.info(result.standardOutput)

        then:
        result.wasExecuted(':test')
    }
}
