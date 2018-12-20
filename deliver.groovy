@Library('env_utils') _
// Parameters made available from Jenkins job configuration:
//   delivery_pipeline
//   delivery_name
//   metapackage (i.e. the environment's base)
//   aux_packages
//   conda_installer_version
//   conda_version
//   remote_host
//   output_dir

def gen_specfiles(label) {

    node(label) {

        // Delete any existing job workspace directory contents.
        // The directory deleted is the one named after the jenkins pipeline job.
        deleteDir()

        unstash "script"

        env.PYTHONPATH = ""
        // Make the log a bit more deterministic
        env.PYTHONUNBUFFERED = "true"

        def WORKDIR = pwd()
        println("WORKDIR = ${WORKDIR}")

        conda.install(conda_installer_version)

        PATH = "${WORKDIR}/miniconda/bin:${PATH}"
        def cpkgs = "conda=${conda_version}"
        def pkg_list = aux_packages.replaceAll('\n', ' ')
        def py_list = python_versions.split('\n')

        def flags = ""
        println("${finalize} - finalize")
        if ("${finalize}" == "true") {
            flags = "--final"
        }

        withEnv(["HOME=${HOME}", "PATH=${PATH}"]) {
            sh "conda install --quiet --yes ${cpkgs}"

            // Generate spec files
            for (String py_version : py_list) {
                sh "${WORKDIR}/mktestenv.sh -d ${delivery_pipeline} -n ${delivery_name} -p ${py_version} -m ${metapackage} ${flags} ${pkg_list}"
            }

            // Make spec files available to master node.
            stash name: "spec-stash-${label}", includes: "${delivery_pipeline}*.txt"
        }
    }
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
        sh "cp -r ${WORKSPACE}@script/*.sh ."
        stash name: "script", includes: "*.sh"
        parallel(
            Linux: { gen_specfiles('RHEL-6') },
            MacOS: { gen_specfiles('OSX-10.11') }
        )
    }

    stage('archive') {
        // Retrieve the spec files from the nodes where they were created.
        unstash "spec-stash-RHEL-6"
        unstash "spec-stash-OSX-10.11"
        hostname = remote_host.tokenize(".")[0]
        withCredentials([usernamePassword(credentialsId: remote_credentials,
            usernameVariable: 'USERNAME',
            passwordVariable: 'PASSWORD')]) {
                sh "rsync -avzr ${delivery_pipeline}*.txt ${USERNAME}@${hostname}:${output_dir}"
           }
    }
}
