import org.jenkinsci.plugins.pipeline.utility.steps.shaded.org.yaml.snakeyaml.Yaml
@Library('env_utils') _
library('utils')
//
// Note: Jenkinsfiles for projects that produce dev packages to be used
//       in the assembling of delivery sets must have the build config
//       used to run the intended regression test suite named 'bc'.
//
// Parameters made available from Jenkins job configuration:
//   delivery_name
//   python_version
//   aux_packages
//   conda_installer_version
//   conda_version
//   output_dir


// To sidestep not-serializable exceptions, encapsulate the YAML reading
// step in a method of its own, used below.
@NonCPS
def readYaml(data) {
  def yaml = new Yaml()
  payload = yaml.load(data)
  return payload
}

def gen_specfiles(label, run_tests) {
    
    node(label) {

        // Delete any existing job workspace directory contents.
        // The directory deleted is the one named after the jenkins pipeline job.
        deleteDir()

        println("PATH ----")
        println("${env.PATH}")

        unstash "script"

        env.PYTHONPATH = ""
        // Make the log a bit more deterministic
        env.PYTHONUNBUFFERED = "true"

        def WORKDIR = pwd()
        println("WORKDIR = ${WORKDIR}")

        def flags = ""
        println("${finalize} - finalize")
        if ("${finalize}" == "true") {
            flags = "--final"
        }

        conda.install()

        path_with_conda = "${WORKDIR}/miniconda/bin:${PATH}"
        def cpkgs = "conda=${conda_version}"
        def pkg_list = aux_packages.replaceAll('\n', ' ')
       

        withEnv(["HOME=${WORKDIR}", "PATH=${path_with_conda}"]) {
            sh "env | sort"
            conda_exe = sh(script: "which conda", returnStdout: true).trim()
            println("Found conda exe at ${conda_exe}.")
            conda_root = conda_exe.replace("/bin/conda", "").trim()
            sh "conda install --quiet --yes ${cpkgs}"

            // Generate spec files
            sh "${WORKDIR}/mktestenv.sh -n ${delivery_name} -p ${python_version} ${flags} ${pkg_list}"
       
            // Make spec files available to master node. 
            stash name: "spec-stash-${label}", includes: "hstdp*.txt"

            // List environments for testing
            sh "conda env list"
            metapkg_list = sh(script:"conda env list", returnStdout: true).trim()
            metapkg_names = []
            for (pkg in metapkg_list) {
                metapkg_names.add(pkg.tokenize()[0].trim()
            }
        }

        // Get source repo for each aux package _at the commit used to
        // build the pkg_.
        // For each package in pkg_list, locate installed package in local conda
        // dir, extract metadata to obtain:
        //    commit hash
        //    source repository
        if (run_tests) {
            def env_name = sh(script: "ls hstdp*", returnStdout: true).trim()[0..-5]
            println("env_name: ${env_name}")
            for (pkg in pkg_list.tokenize()) {
                println("Extracting metadata for ${pkg}...")
                pkg_name = pkg.tokenize('=')[0]
                println("pkg_name: ${pkg_name}")
                pkg_version = pkg.tokenize('=')[1]
                ccmd = "${conda_exe} list -n ${env_name} | grep ${pkg_name} | grep dev"
                pkg_info = sh(script: ccmd,
                               returnStdout: true).trim()
                println("pkg_info: ${pkg_info}")
                meta_base = pkg_info.tokenize()[1]
                meta_rev = pkg_info.tokenize()[2]
                meta_dir = "${pkg_name}-${meta_base}-${meta_rev}"
                metafile = "${conda_root}/pkgs/${meta_dir}/info/recipe/meta.yaml"
                filedata = readFile(metafile)
                meta = readYaml(filedata)
                git_url = meta['source'].git_url

                // Use this clone to run the full test suite.
                sh "git clone ${git_url} ./${pkg_name}"

                // For development only. Disable before use.
                //if (pkg_name == "hstcal") {
                //    jenkinsfile = "./JenkinsfileRT"
                //} else {
                    jenkinsfile = "${WORKDIR}/${pkg_name}/JenkinsfileRT"
                //}


                // Only run tests if JenkinsfileRT exists for project.
                if (fileExists(jenkinsfile)) {

                    // Post-process each -dev project's JenkinsfileRT to allow
                    // importing of only the configuration values without running
                    // the processing machinery called within which would complicate
                    // matters here.
                    // Disable scm_checkout call in project's JenkinsfileRT.
                    // NOTE: 'if' statement must be on same line as scm_checkout call.
                    // TODO: Generalize to make this more robust against layout
                    //       changes in file.
                    sh "sed -i 's/^\\s*if/\\/\\/if/' ${jenkinsfile}"
                    // Disable utils.run line in projects's JenkinsfileRT.
                    sh "sed -i 's/^\\s*utils.run/\\/\\/utils.run/' ${jenkinsfile}"

                    println("About lo load local modified JenkinsfileRT")
                    sh "ls -al"
                    // Add declarations from file to this namespace. Provides 'bc'.
                    // TODO: Iterate through namespace to locate buildConfig
                    // objects without a standard name?
                    // NOTE: 'evaluate' runs the methods it encounters.
                    //       Edit to disable to prevent undesired side effects.
                    jf = evaluate readFile(jenkinsfile)

                    def conda_prefix = "${conda_root}/envs/${env_name}".trim()

                    def path_found = false
                    for (var in bc.env_vars) {
                        println("--> ${var}")
                        if (var[0..3] == 'PATH') {
                            path_found = true
                            pathvar_idx = bc.env_vars.indexOf(var)
                            bc.env_vars.remove(pathvar_idx)
                            println(" --- ADJUSTING PATH FOR CONDA ENV ----")
                            var = "PATH=${conda_prefix}/bin:${WORKDIR}/miniconda/bin:${var[5..-1]}"
                            println(var)
                            bc.env_vars.add(var)
                        }
                    }
                    if (!path_found) {
                        println("--- Adding PATH prefix for conda environment and conda exe.")
                        bc.env_vars.add("PATH=${conda_prefix}/bin:${WORKDIR}/miniconda/bin:\$PATH")
                    }
                    bc.env_vars.add("ENVIRONMENT=VARIABLE-${pkg}")

                    def bconfig = utils.expandEnvVars(bc) // expand vars into .runtime

                    def env_vars = bconfig.runtime
                    // 'Activate' conda env and operate within it.
                    env_vars.add(0, "CONDA_SHLVL=1")
                    env_vars.add(0, "CONDA_PROMPT_MODIFIER=${env_name}")
                    env_vars.add(0, "CONDA_EXE=${conda_exe}")
                    env_vars.add(0, "CONDA_PREFIX=${conda_prefix}")
                    env_vars.add(0, "CONDA_PYTHON_EXE=${conda_prefix}/bin/python")
                    env_vars.add(0, "CONDA_DEFAULT_ENV=${env_name}")

                    println('FINAL ENV VARS')
                    for (var in env_vars) {
                        println(var)
                    }

                    // Run testing commands
                    withEnv(env_vars) {
                        println("============= withEnv ==========")
                        sh "env | sort"
                        // Obtain pip for build/install use
                        sh "conda install pip"
                        dir(pkg_name) {
                           sh "ls -l"
                           def conda_pkgs = ""
                           for (cpkg in bc.conda_packages) {
                               conda_pkgs = "${conda_pkgs} ${cpkg} "
                           }
                           sh "conda install ${conda_pkgs} pytest -c http://ssb.stsci.edu/astroconda -c defaults -q -y"

                           // If setup.py exists in project, run `python setup.py build` to prepare
                           // local source tree for testing. This is required for projects with C
                           // extensions.
                           if (fileExists("setup.py")) {
                               sh(script: "pip install --no-deps -e .")
                           }
                           println("Test commands(s):")
                           // TODO: Remove after 2019.2 delivery.
                           if (pkg_name == 'calcos') {
                               sh(script: "conda install ci-watson")
                           }
                           println(bc.test_cmds)
                           for (tcmd in bc.test_cmds) {
                               sh(script: "${tcmd} || true")
                           }
                           // Uninstall packages pulled in for this test run, leaving
                           // any that were already present.
                           def remove_pkgs = ""
                           for (rpkg in conda_pkgs.split()) {
                               if ('=' in rpkg) {
                                   rpkg = rpkg.split('=')[0].trim()
                               }
                               if (!(rpkg in metapkg_names)) {
                                   remove_pkgs = "${remove_pkgs} ${rpkg}"
                               }
                               
                           }
                           
                           //sh "conda remove ${conda_pkgs} --force -y"
                           sh "conda remove ${remove_pkgs} --force -y"

                           // Read in test reports for display.
                           // TODO: Use shared lib for this functionality.
                           // Process the results file to include the build config name as a prefix
                           // on each test name so that it is obvious from where each test result
                           // comes.
                           report_exists = sh(script: "test -e *.xml", returnStatus: true)
                           if (report_exists == 0) {
                               repfile = sh(script:"find *.xml", returnStdout: true).trim()
                               command = "cp ${repfile} ${repfile}.modified"
                               sh(script:command)
                               sh(script:"sed -i 's/ name=\"/ name=\"[${pkg_name}:${bc.name}] /g' *.xml.modified")
                               step([$class: 'XUnitBuilder',
                                   thresholds: [
                                   [$class: 'SkippedThreshold', unstableThreshold: "${bc.skippedUnstableThresh}"],
                                   [$class: 'SkippedThreshold', failureThreshold: "${bc.skippedFailureThresh}"],
                                   [$class: 'FailedThreshold', unstableThreshold: "${bc.failedUnstableThresh}"],
                                   [$class: 'FailedThreshold', failureThreshold: "${bc.failedFailureThresh}"]],
                                   tools: [[$class: 'JUnitType', pattern: '*.xml.modified']]])
                           } else {
                               println("No .xml files found in directory ${pkg_name}. Test report ingestion skipped.")
                           }
                        }
                    }
                } else {
                    println("JenkinsfileRT not found for ${pkg_name}, skipping test run.")
                } //end if(fileExists(jenkinsfile))
            } // endfor pkg
        }

        stage('archive') {
            hostname = sh(script: "hostname", returnStdout: true).tokenize(".")[0]
            println(hostname)
            withCredentials([usernamePassword(credentialsId: '322ad15d-2f5b-4d06-87fa-b45a88596f30',
                usernameVariable: 'USERNAME',
                passwordVariable: 'PASSWORD')]) {
                    sh "rsync -avzr hstdp*.txt ${USERNAME}@${hostname}:${output_dir}"
               }
        }

    } // end node()
}


node('master') {
    // Run the gen_specfiles operation for each platform in parallel.
    properties(
        [buildDiscarder(
            logRotator(artifactDaysToKeepStr: '',
                       artifactNumToKeepStr: '',
                       daysToKeepStr: '',
                       numToKeepStr: '4')), pipelineTriggers([])])
    stage('create specfiles') {
        deleteDir()
        sh "cp -r ${WORKSPACE}@script/* ."
        // Carry delivery_control files into each remote node's workspace.
        stash name: "script", includes: "*"
        parallel(
            // Generate spec files. Only run tests on Linux (for now).
            Linux: { gen_specfiles('RHEL-6', true) },
            //MacOS: { gen_specfiles('OSX-10.13', false) }
        )
    }
}
