// Import the utility functionality.
import jobs.generation.*;

// Defines a the new of the repo, used elsewhere in the file
def project = GithubProject
def branch = GithubBranchName

// Generate the builds for debug and release, commit and PRJob
[true, false].each { isPR -> // Defines a closure over true and false, value assigned to isPR
    ['Debug', 'Release'].each { configuration ->

        def newJobName = Utilities.getFullJobName(project, "windows_${configuration.toLowerCase()}", isPR)

        def newJob = job(newJobName) {
            // This opens the set of build steps that will be run.
            steps {
                // Indicates that a batch script should be run with the build string (see above)
                // Also available is:
                // shell (for unix scripting)
                batchFile("""SET VS150COMNTOOLS=%ProgramFiles(x86)%\\Microsoft Visual Studio\\2017\\Enterprise\\Common7\\Tools\\
SET VSSDK150Install=%ProgramFiles(x86)%\\Microsoft Visual Studio\\2017\\Enterprise\\VSSDK\\
SET VSSDKInstall=%ProgramFiles(x86)%\\Microsoft Visual Studio\\2017\\Enterprise\\VSSDK\\

build.cmd /no-deploy-extension /${configuration.toLowerCase()}""")
            }
        }

        def archiveSettings = new ArchivalSettings()
        archiveSettings.addFiles("bin/**/*")
        archiveSettings.excludeFiles("bin/obj/*")
        archiveSettings.setFailIfNothingArchived()
        archiveSettings.setArchiveOnFailure()
        Utilities.addArchival(newJob, archiveSettings)
        Utilities.setMachineAffinity(newJob, 'Windows_NT', 'latest-or-auto-dev15-rc')
        Utilities.standardJobSetup(newJob, project, isPR, "*/${branch}")
        Utilities.addXUnitDotNETResults(newJob, "**/*TestResults.xml")
        if (isPR) {
            Utilities.addGithubPRTriggerForBranch(newJob, branch, "Windows ${configuration}")
        }
        else {
            Utilities.addGithubPushTrigger(newJob)
        }
    }
}

// Add VSI jobs.
// Generate the builds for commit and PRJob
[true, false].each { isPR -> // Defines a closure over true and false, value assigned to isPR
    def newVsiJobName = Utilities.getFullJobName(project, "vsi", isPR)

    def newVsiJob = job(newVsiJobName) {
        description('')

        // This opens the set of build steps that will be run.
        steps {
            // Build roslyn-project-system repo - we also need to set certain environment variables for building the repo with VS15 toolset.
            batchFile("""SET VS150COMNTOOLS=%ProgramFiles(x86)%\\Microsoft Visual Studio\\2017\\Enterprise\\Common7\\Tools\\
SET VSSDK150Install=%ProgramFiles(x86)%\\Microsoft Visual Studio\\2017\\Enterprise\\VSSDK\\
SET VSSDKInstall=%ProgramFiles(x86)%\\Microsoft Visual Studio\\2017\\Enterprise\\VSSDK\\

build.cmd /release /skiptests""")

            // Patch all the MSBuild xaml and targets files from the current roslyn-project-system commit into VS install.
            batchFile("""SET VS_MSBUILD_MANAGED=%ProgramFiles(x86)%\\Microsoft Visual Studio\\2017\\Enterprise\\MSBuild\\Microsoft\\VisualStudio\\Managed

mkdir backup
xcopy /SIY "%VS_MSBUILD_MANAGED%" .\\backup\\Managed

xcopy /SIY .\\src\\Targets\\*.targets "%VS_MSBUILD_MANAGED%"
xcopy /SIY .\\bin\\Release\\Rules\\*.xaml "%VS_MSBUILD_MANAGED%"
""")

            // Sync roslyn-internal upfront as we use the VsixExpInstaller from roslyn-internal repo to install VSIXes built from SDK repo into RoslynDev hive.
            batchFile("""pushd %WORKSPACE%\\roslyn-internal
git submodule init
git submodule sync
git submodule update --init --recursive
init.cmd
popd""")

            // Build sdk repo and install templates into RoslynDev hive.
            batchFile("""SET VS150COMNTOOLS=%ProgramFiles(x86)%\\Microsoft Visual Studio\\2017\\Enterprise\\Common7\\Tools\\
SET DeveloperCommandPrompt=%VS150COMNTOOLS%\\VsMSBuildCmd.bat

call "%DeveloperCommandPrompt%" || goto :BuildFailed

pushd %WORKSPACE%\\sdk
call build.cmd -Configuration release -SkipTests || goto :BuildFailed

pushd %WORKSPACE%\\roslyn-internal\\Closed\\Tools\\Source\\VsixExpInstaller
msbuild /p:Configuration=Release
SET VSIXExpInstallerExe=%WORKSPACE%\\roslyn-internal\\Open\\Binaries\\Release\\Exes\\VsixExpInstaller\\VsixExpInstaller.exe
%VSIXExpInstallerExe% /u /rootsuffix:RoslynDev %WORKSPACE%\\sdk\\bin\\Release\\Microsoft.VisualStudio.ProjectSystem.CSharp.Templates.vsix
%VSIXExpInstallerExe% /rootsuffix:RoslynDev %WORKSPACE%\\sdk\\bin\\Release\\Microsoft.VisualStudio.ProjectSystem.CSharp.Templates.vsix
%VSIXExpInstallerExe% /u /rootsuffix:RoslynDev %WORKSPACE%\\sdk\\bin\\Release\\Microsoft.VisualStudio.ProjectSystem.VisualBasic.Templates.vsix
%VSIXExpInstallerExe% /rootsuffix:RoslynDev %WORKSPACE%\\sdk\\bin\\Release\\Microsoft.VisualStudio.ProjectSystem.VisualBasic.Templates.vsix

exit /b %ERRORLEVEL%

:BuildFailed
echo Build failed with ERRORLEVEL %ERRORLEVEL%
exit /b 1
""")

            // Build roslyn-internal and run netcore VSI tao tests.
            batchFile("""SET VS150COMNTOOLS=%ProgramFiles(x86)%\\Microsoft Visual Studio\\2017\\Enterprise\\Common7\\Tools\\
SET VSSDK150Install=%ProgramFiles(x86)%\\Microsoft Visual Studio\\2017\\Enterprise\\VSSDK\\
SET VSSDKInstall=%ProgramFiles(x86)%\\Microsoft Visual Studio\\2017\\Enterprise\\VSSDK\\

pushd %WORKSPACE%\\roslyn-internal
set TEMP=%WORKSPACE%\\roslyn-internal\\Open\\Binaries\\Temp
mkdir %TEMP%
set TMP=%TEMP%

set EchoOn=true

BuildAndTest.cmd -build:true -clean:false -deployExtensions:true -trackFileAccess:false -officialBuild:false -realSignBuild:false -parallel:true -release:true -delaySignBuild:true -samples:false -unit:false -eta:false -vs:true -cibuild:true -x64:false -netcoretestrun
popd""")

           // Revert patched targets and rules from backup.
            batchFile("""SET VS_MSBUILD_MANAGED=%ProgramFiles(x86)%\\Microsoft Visual Studio\\2017\\Enterprise\\MSBuild\\Microsoft\\VisualStudio\\Managed
del /SQ "%VS_MSBUILD_MANAGED%\\"
xcopy /SIY .\\backup\\Managed "%VS_MSBUILD_MANAGED%"
rmdir /S /Q backup
""")
        }
    }

    addVsiArchive(newVsiJob)
    Utilities.setMachineAffinity(newVsiJob, 'Windows_NT', 'latest-or-auto-dev15-internal')
    Utilities.standardJobSetup(newVsiJob, project, isPR, "*/${branch}")
    // ISSUE: Temporary until a full builder for source control is available.
    addVsiMultiScm(newVsiJob, project)

    if (isPR) {
        def triggerPhrase = generateTriggerPhrase(newVsiJobName, "vsi")
        Utilities.addGithubPRTriggerForBranch(newVsiJob, branch, newVsiJobName, triggerPhrase, /*triggerPhraseOnly*/ true)
    } else {
        Utilities.addGithubPushTrigger(newVsiJob)        
    }

    Utilities.addHtmlPublisher(newVsiJob, "roslyn-internal/Open/Binaries/Release/Exes/EditorTestApp/VSIntegrationTestLogs", 'VS Integration Test Logs', '*.html')
}

// Archive VSI artifacts.
static void addVsiArchive(def myJob) {
  def archiveSettings = new ArchivalSettings()
  archiveSettings.addFiles('roslyn-internal/Open/Binaries/**/*.pdb')
  archiveSettings.addFiles('roslyn-internal/Open/Binaries/**/*.xml')
  archiveSettings.addFiles('roslyn-internal/Open/Binaries/**/*.log')
  archiveSettings.addFiles('roslyn-internal/Open/Binaries/**/*.zip')
  archiveSettings.addFiles('roslyn-internal/Open/Binaries/**/*.png')
  archiveSettings.addFiles('roslyn-internal/Open/Binaries/**/*.xml')
  archiveSettings.excludeFiles('roslyn-internal/Open/Binaries/Obj/**')
  archiveSettings.excludeFiles('roslyn-internal/Open/Binaries/Bootstrap/**')

  archiveSettings.setArchiveOnFailure()
  archiveSettings.setFailIfNothingArchived()
  Utilities.addArchival(myJob, archiveSettings)
}

// ISSUE: Temporary until a full builder for multi-scm source control is available.
// Replace the scm settings with a multiScm setup.  Note that this will not work for PR jobs
static void addVsiMultiScm(def myJob, def project) {
    myJob.with {
        multiscm {
            git {
                remote {
                    // Use the input project
                    github(project)
                }
                // Pull from the desired branch input branch passed as a parameter (set up by standardJobSetup)
                branch('${GitBranchOrCommit}')
            }
            git {
                remote {
                    url('https://github.com/dotnet/sdk')
                }
                extensions {
                    relativeTargetDirectory('sdk')
                }
                // pull in a specific LKG commit from master.
                branch('c82725bc657ad369ecd4e59bf860acf6205027b6')
            }
            git {
                remote {
                    url('https://github.com/dotnet/roslyn-internal')
                    credentials('dotnet-bot-private-repo-token')
                }
                extensions {
                    relativeTargetDirectory('roslyn-internal')
                }
                // roslyn-internal - pull in a specific LKG commit from master.
                // In future, '*/master' can be placed here to pull latest sources.
                branch('808de0c5801b309ae3a13f201a1486ff6a91df57')
            }
        }
    }
}
// END ISSUE

static String generateTriggerPhrase(String jobName, String triggerPhraseExtra) {
    def triggerCore = "all|${jobName}"
    if (triggerPhraseExtra) {
        triggerCore = "${triggerCore}|${triggerPhraseExtra}"
    }
    return "(?i).*test\\W+(${triggerCore})\\W+please.*";
}

// Make the call to generate the help job
Utilities.createHelperJob(this, project, branch,
    "Welcome to the ${project} Repository",  // This is prepended to the help message
    "Have a nice day!")  // This is appended to the help message.  You might put known issues here.