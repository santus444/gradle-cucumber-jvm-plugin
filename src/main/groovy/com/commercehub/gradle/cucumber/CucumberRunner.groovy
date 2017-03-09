package com.commercehub.gradle.cucumber

import groovy.util.logging.Slf4j
import groovyx.gpars.GParsPool
import net.masterthought.cucumber.Configuration
import net.masterthought.cucumber.ReportParser
import net.masterthought.cucumber.json.Feature
import org.gradle.api.GradleException
import org.gradle.api.file.FileTree
import org.gradle.api.tasks.SourceSet

import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Created by jgelais on 6/16/15.
 */
@Slf4j
class CucumberRunner {
    private static final String PLUGIN = '--plugin'

    CucumberRunnerOptions options
    CucumberTestResultCounter testResultCounter
    Map<String, String> systemProperties
    Configuration configuration

    CucumberRunner(CucumberRunnerOptions options, Configuration configuration,
                   CucumberTestResultCounter testResultCounter, Map<String, String> systemProperties) {
        this.options = options
        this.testResultCounter = testResultCounter
        this.configuration = configuration
        this.systemProperties = systemProperties
    }

    boolean run(SourceSet sourceSet, File resultsDir, File reportsDir) {
        AtomicBoolean hasFeatureParseErrors = new AtomicBoolean(false)

        def features = findFeatures(sourceSet)

        testResultCounter.beforeSuite(features.files.size())
        GParsPool.withPool(options.maxParallelForks) {
            features.files.eachParallel { File featureFile ->
                String featureName = getFeatureNameFromFile(featureFile, sourceSet)
                File resultsFile = new File(resultsDir, "${featureName}.json")
                File consoleOutLogFile = new File(resultsDir, "${featureName}-out.log")
                File consoleErrLogFile = new File(resultsDir, "${featureName}-err.log")
                File junitResultsFile = new File(resultsDir, "${featureName}.xml")

                List<String> args = []
                applyGlueArguments(args)
                applyPluginArguments(args, resultsFile, junitResultsFile)
                applyDryRunArguments(args)
                applyMonochromeArguments(args)
                applyStrictArguments(args)
                applyTagsArguments(args)
                applySnippetArguments(args)
                args << featureFile.absolutePath

                new JavaProcessLauncher('cucumber.api.cli.Main', sourceSet.runtimeClasspath.toList())
                        .setArgs(args)
                        .setConsoleOutLogFile(consoleOutLogFile)
                        .setConsoleErrLogFile(consoleErrLogFile)
                        .setSystemProperties(systemProperties)
                        .execute()

                if (resultsFile.exists()) {
                    handleResult(resultsFile, consoleOutLogFile, hasFeatureParseErrors, sourceSet)
                } else {
                    hasFeatureParseErrors.set(true)
                    if (consoleErrLogFile.exists()) {
                        log.error(consoleErrLogFile.text)
                    }
                }
            }
        }

        if (hasFeatureParseErrors.get()) {
            throw new GradleException('One or more feature files failed to parse. See error output above')
        }

        testResultCounter.afterSuite()
        return !testResultCounter.hadFailures()
    }

    String getFeatureNameFromFile(File file, SourceSet sourceSet) {
        String featureName = file.name
        sourceSet.resources.srcDirs.each { File resourceDir ->
            if (isFileChildOfDirectory(file, resourceDir)) {
                featureName = convertPathToPackage(getReleativePath(file, resourceDir))
            }
        }

        return featureName
    }

    List<Feature> parseFeatureResult(File jsonReport) {
        return new ReportParser(configuration).parseJsonFiles([jsonReport.absolutePath])
    }

    CucumberFeatureResult createResult(Feature feature) {
        CucumberFeatureResult result = new CucumberFeatureResult(
                totalScenarios: feature.passedScenarios + feature.failedScenarios,
                failedScenarios: feature.failedScenarios,
                totalSteps: feature.passedSteps + feature.failedSteps,
                failedSteps: feature.failedSteps,
                skippedSteps: feature.skippedSteps,
                pendingSteps: feature.pendingSteps,
                undefinedSteps: feature.undefinedSteps
        )

        return result
    }

    protected void applySnippetArguments(List<String> args) {
        args << '--snippets'
        args << options.snippets
    }

    protected void applyTagsArguments(List<String> args) {
        if (!options.tags.isEmpty()) {
            args << '--tags'
            args << options.tags.join(',')
        }
    }

    protected void applyStrictArguments(List<String> args) {
        if (options.isStrict) {
            args << '--strict'
        }
    }

    protected void applyMonochromeArguments(List<String> args) {
        if (options.isMonochrome) {
            args << '--monochrome'
        }
    }

    protected void applyDryRunArguments(List<String> args) {
        if (options.isDryRun) {
            args << '--dry-run'
        }
    }

    protected void applyPluginArguments(List<String> args, File resultsFile, File junitResultsFile) {
        args << PLUGIN
        args << "json:${resultsFile.absolutePath}"
        if (options.junitReport) {
            args << PLUGIN
            args << "junit:${junitResultsFile.absolutePath}"
        }
    }

    protected List<String> applyGlueArguments(List<String> args) {
        options.stepDefinitionRoots.each {
            args << '--glue'
            args << it
        }
    }

    protected FileTree findFeatures(SourceSet sourceSet) {
        sourceSet.resources.matching {
            options.featureRoots.each {
                include("${it}/**/*.feature")
            }
        }
    }

    private void handleResult(File resultsFile, File consoleOutLogFile,
                              AtomicBoolean hasFeatureParseErrors, SourceSet sourceSet) {
        List<CucumberFeatureResult> results = parseFeatureResult(resultsFile).collect {
            log.debug("Logging result for $it.name")
            createResult(it)
        }
        results.each { CucumberFeatureResult result ->
            testResultCounter.afterFeature(result)

            if (result.hadFailures()) {
                if (result.undefinedSteps > 0) {
                    hasFeatureParseErrors.set(true)
                }
                log.error('{}:\r\n {}', sourceSet.name, consoleOutLogFile.text)
            }
        }
    }

    private String convertPathToPackage(Path path) {
        return path.toString().replace(File.separator, '.')
    }

    private Path getReleativePath(File file, File dir) {
        return Paths.get(dir.toURI()).relativize(Paths.get(file.toURI()))
    }

    private boolean isFileChildOfDirectory(File file, File dir) {
        Path child = Paths.get(file.toURI())
        Path parent = Paths.get(dir.toURI())
        return child.startsWith(parent)
    }
}
