package org.sample.jenkins

import org.sample.Jenkins.*

import org.jenkinsci.plugins.workflow.cps.CpsScript
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

class CSharpBuilder {
    private List analyses = []
    private CpsScript script
    private def env = {}
    private Configuration config = null
    private SlackBuilder slack = null
    // A bit of a limitation of the current implementation is it is linear. It makes it
    // easy to track the current stage and when a failure occurs to report it, however.
    private List stages = []
    private String currentStage = null


    CSharpBuilder(CpsScript script) {
        this.script = script
        this.env = script.env
    }

    void addStage(String name, Closure method) {
        addStageIfTrue(name, method, null)
    }

    void addStageIfTrue(String name, Closure method, Closure runIfTrue) {
        stages << [
            name: name,
            run: method,
            runIfTrue: runIfTrue
        ]
    }

    void run(nodeLabel = null) {
        script.parameters {
            booleanParam(name: 'clean', defaultValue: false, description: 'Whether to clean the workspace before starting.')
        }        

        script.node(label: nodeLabel) {
            try {
                wrappedRun()
            } catch (Exception e) {
                script.currentBuild.result = 'FAILURE'
                if(slack) {
                    if(currentStage) {
                        slack.addInlineMessage("The build failed during ${currentStage}:\n\n${e.getMessage()}")
                    } else {
                        slack.addInlineMessage("The build failed: ${e.getMessage()}")
                    }
                }
                throw e
            } finally {
                try {
                    currentStage = null
                    scanBuild("MSBuild warnings and errors", "No MSBuild warnings or errors", script.msBuild())

                    notifyBuildStatus(BuildNotifyStatus.fromText(script.currentBuild.result))
                } catch (Exception e) {
                    script.currentBuild.result = 'FAILURE'
                    if(slack) {
                        slack.addInlineMessage("The build failed: ${e.getMessage()}")
                    }
                    notifyBuildStatus(BuildNotifyStatus.Failure)
                    throw e
                } finally {
                    script.echo('Archiving artifacts...')
                    String additional = config.getAdditionalBuildArtifacts() 
                    String artifacts = additional ? "logs/**,${additional}" : 'logs/**'
                    script.archiveArtifacts(artifacts: artifacts, allowEmptyArchive: true, onlyIfSuccessful: false)
                    script.cleanWs()
                }
            }
        }
    }

    private boolean isMainBranch() {
        return env.BRANCH_NAME.toLowerCase() in ['main', 'master']
    }

    private void initializeScriptProperties() {
        // Configure properties and triggers.
        List properties = []
        List triggers = []
        triggers.add(script.pollSCM(config.getScmTrigger()))
        properties.add(script.disableConcurrentBuilds())
        properties.add(script.disableResume())
        properties.add(script.pipelineTriggers(triggers))
        script.properties(properties)
    }

    private void wrappedRun()
    {
        // Clean the workspace.
        if(script.params.clean != null && script.params.clean != false) {
            script.cleanWs(disableDeferredWipeout: false, deleteDirs: true)
        }

        // Populate the workspace
        script.checkout(script.scm).each { k,v -> env.setProperty(k, v) }

        // Can't access files until we have a node and workspace.
        config = Configuration.read(script, 'Configuration.json')
        
        initializeScriptProperties()

        slack = new SlackBuilder(config)

        populateStages()

        script.withEnv(["MSBUILDDEBUGPATH=${script.env.WORKSPACE}/logs"]) {
            stages.each { stg ->
                currentStage = stg.name
                script.stage(stg.name) {
                    if(stg.runIfTrue && !stg.runIfTrue()) {
                        Utils.markStageSkippedForConditional(stg.name)
                    } else {
                        stg.run()
                    }
                }
            }
            currentStage = null
        }
    }

    private void populateStages() {

        addStage('Send Start Notification', 
            { notifyBuildStatus(BuildNotifyStatus.Pending) })

        addStage('Setup for forensics',
            { script.discoverGitReferenceBuild() })

        addStage('Get Environment', 
            { script.bat("set") })

        //  '--no-cache' to avoid a shared cache--if multiple projects are running NuGet restore, they can collide.
        addStage('Restore NuGet For Solution',
            { script.bat("dotnet restore --nologo --no-cache") })

        addStageIfTrue("Build Solution - ${config.getTestBuildConfiguration()}",
            { script.bat("dotnet build --nologo -c ${config.getTestBuildConfiguration()} -p:PackageVersion=${config.getNugetVersion()} -p:Version=${config.getVersion()} --no-restore") },
            { return config.getRunTests() })
            
        // MSTest projects automatically include coverlet that can generate cobertura formatted coverage information.
        addStageIfTrue('Run Tests', 
            { script.bat("dotnet test --nologo -c ${config.getTestBuildConfiguration()} --results-directory TestResults --logger trx --collect:\"XPlat code coverage\" --no-restore --no-build") },
            { return config.getRunTests() })
 
        addStageIfTrue('Publish Test Output', 
            {
                def tests = gatherTestResults('TestResults/**/*.trx')
                def coverage = gatherCoverageResults('TestResults/**/In/**/*.cobertura.xml')
                slack.addThreadedMessage("\n${tests}\n${coverage}")
                script.mstest(testResultsFile:"TestResults/**/*.trx", failOnError: true, keepLongStdio: true)
            },
            { return config.getRunTests() })

        addStageIfTrue('Publish Code Coverage',
            {
                script.publishCoverage(adapters: [
                    script.coberturaAdapter(path: "TestResults/**/In/**/*.cobertura.xml", mergeToOneReport: true, thresholds: config.getCoverageThresholds())
                ], failNoReports: true, failUnhealthy: true, calculateDiffForChangeRequests: true)
                if(script.currentBuild.result == 'FAILURE') {
                    throw new Exception('Code coverage results failed.')
                }
            },
            { return config.getRunTests() })

        addStageIfTrue('Clean', 
            {
                script.bat("dotnet clean -c ${config.getTestBuildConfiguration()} --nologo")
                script.bat(returnStatus: true, script: "del /s /q *.nupkg *.snupkg") // We don't care if this fails, that means it didn't find anything to delete.
            },
            { return config.getRunTests() })

        addStage("Build Solution - ${config.getNugetBuildConfiguration()}", 
            { script.bat("dotnet build --nologo -c ${config.getNugetBuildConfiguration()} -p:PackageVersion=${config.getNugetVersion()} -p:Version=${config.getVersion()} --no-restore") })

        addStage('Run Security Scan',
            {
                script.bat("dotnet new tool-manifest")
                script.bat("dotnet tool install --local security-scan --no-cache")

                String slnFile = getUniqueFileByExtension('.sln', true)
                if(slnFile.length() == 0) {
                    slnFile = getUniqueFileByExtension('.csproj')
                }

                script.bat("dotnet security-scan \"${slnFile}\" --excl-proj=**/*Test*/** -n --cwe --export=sast-report.sarif")

                scanBuild("Static analysis results", "No static analysis issues to report", script.sarif(pattern: 'sast-report.sarif'), true, true)
            })

        String nugetSource = config.getNugetSource()
        addStageIfTrue('Preexisting NuGet Package Check', 
            {
                def packages = getPublishedNugetPackages(nugetSource)
                List duplicatePackages = []

                // Find all the nuget packages to publish.
                def nupkgFiles = "**/*.nupkg"
                script.findFiles(glob: nupkgFiles).each { nugetPkg ->
                    String pkgName = nugetPkg.getName()
                    pkgName = pkgName.substring(0, pkgName.length() - 6) // Remove extension
                    if(packages.contains(pkgName)) {
                        duplicatePackages << pkgName
                    } else {
                        script.echo "The package ${nugetPkg} is not in the NuGet repository."
                    }
                }
                if(duplicatePackages.size()) {
                    script.error "The following packages are already in the NuGet repository:\n\t${duplicatePackages.join('\t\n')}"
                }
            },
            {
                if(!nugetSource) {
                    script.echo "The 'NugetSource' configuration setting is required to query existing published packages."
                }
                return nugetSource != null
            })

        String nugetCredentialsId = config.getNugetKeyCredentialsId()
        addStageIfTrue('NuGet Publish',
            {
                List published = []
                // We are only going to publish to NuGet when the branch is main or master.
                // This way other branches will test without interfering with releases.
                script.withCredentials([script.string(credentialsId: nugetCredentialsId, variable: 'APIKey')]) { 
                    // Find all the nuget packages to publish.
                    def nupkgFiles = "**/*.nupkg"
                    script.findFiles(glob: nupkgFiles).each { nugetPkg ->
                        script.bat("dotnet nuget push \"${nugetPkg}\" --api-key \"%APIKey%\" --source \"${nugetSource}\"")
                        published << nugetPkg.getName()
                    }
                }

                if(published.size()) {
                    if(slack) {
                        slack.addInlineMessage("NuGet packages published:\n\t${published.join('\n\t')}")
                    }
                }
            },
            {
                if(!isMainBranch()) {
                    script.echo "Nuget publishing is disabled outside the main branch."
                    return false
                }
                if(!nugetSource || !nugetCredentialsId) {
                    script.echo "The 'NugetSource' and 'NugetKeyCredentialsId' configuration settings are required to publish nuget packages."
                    return false
                }
                return true
            })
    }

    private String getUniqueFileByExtension(String extension, boolean allowNone = false) {
        String foundFile = ""
        int count = 0
        // Search the repository for a file ending in extension.
        script.findFiles(glob: '**').each {
            String path = it.toString();
            if(path.toLowerCase().endsWith(extension)) {
                foundFile = path;
                count += 1
                if(count > 1) {
                    throw new Exception("Too many files ending in ${extension} were found in the repository.")
                }
            }
        }
        if(!allowNone && foundFile.length() == 0) {
            throw new Exception("No files were found in the repository ending in ${extension}.")
        }

        return foundFile
    }

    private List getPublishedNugetPackages(String nugetSource) {
        def tool = script.tool('NuGet-2022')
        String packageText = script.bat(returnStdout: true, script: "\"${tool}\" list -NonInteractive -Source \"${nugetSource}\"")
        packageText = packageText.replaceAll('\r', '')
        def packages = new ArrayList(packageText.split('\n').toList())
        packages.removeAll { line -> line.toLowerCase().startsWith('warning: ') }
        return packages.collect { pkg -> pkg.replaceAll(' ', '.') }
    }
    
    private void scanBuild(String found, String notFound, def tool, boolean enabledForFailure = false, boolean failOnError = false) {
        def analysisIssues = script.scanForIssues(tool: tool)
        analyses << analysisIssues
        def analysisText = getAnaylsisResultsText(analysisIssues)
        if(analysisText.length() > 0) {
            slack.addThreadedMessage("${found}:\n" + analysisText)
        } else {
            slack.addThreadedMessage("${notFound}.")
        }
        // Rescan. If we collect and then aggregate, warnings become errors
        script.recordIssues(aggregatingResults: true, enabledForFailure: enabledForFailure, failOnError: failOnError, skipPublishingChecks: true, tool: tool)
    }

    private void notifyBuildStatus(BuildNotifyStatus status) {
        script.echo "Setting build status to: ${status.notifyText}"
        if(slack) {
            slack.send(status)
        }
        status.githubStatus.setStatus(config, "Build ${status.notifyText}")
    }

    private String gatherTestResults(String searchPath) {
        Closure<Map> gatherer = { xml ->
            def counters = xml['ResultSummary']['Counters']

            return [
                total: counters['@total'][0].toInteger(),
                passed: counters['@passed'][0].toInteger(),
                failed: counters['@failed'][0].toInteger()
            ]
        }
        Map results = Generic.gatherXmlResults(script, searchPath, gatherer)

        if(results.files == 0) {
            return "No test results found."
        } 
        
        int total = results.total
        int passed = results.passed
        int failed = results.failed

        if(failed == 0) {
            if(passed == 1) {
                return "The only test passed!"
            } else {
                return "All ${total} tests passed!"
            }
        } else {
            return "${failed} of ${total} tests failed!"
        }
    }

    private String gatherCoverageResults(String searchPath) {
        Closure<Map> gatherer = { xml ->
            return [
                linesCovered: xml['@lines-covered'].toInteger(),
                linesValid: xml['@lines-valid'].toInteger()
            ]
        }

        Map results = Generic.gatherXmlResults(script, searchPath, gatherer)

        if(results.files == 0) {
            return "No code coverage results were found to report."
        } 

        int linesCovered = results.linesCovered
        int linesValid = results.linesValid

        if(linesCovered == 0) {
            return "No code lines were found to collect test coverage for."
        }
        
        double pct = linesCovered.toDouble() * 100 / linesValid.toDouble()
        return "${linesCovered} of ${linesValid} lines were covered by testing (${pct.round(1)}%)."
    }

    private String getAnaylsisResultsText(def analysisResults) {
        String issues = ""
        analysisResults.getIssues().each { issue ->
            issues = issues + "* ${issue}\n"
        }
        return issues
    }
}