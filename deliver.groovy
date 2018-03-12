@Library('utils') _
// Parameters made available from Jenkins job configuration:
//   delivery_name
//   delivery_iteration
//   aux_packages
//   conda_installer_version
//   conda_version

def gen_specfiles(label) {
    
    node(label) {

        // Delete any existing job workspace directory contents.
        // The directory deleted is the one named after the jenkins pipeline job.
        deleteDir()

        unstash "script"
        sh "pwd; ls -al"

        env.PYTHONPATH = ""
        // Make the log a bit more deterministic
        env.PYTHONUNBUFFERED = "true"

        def WORKDIR = pwd()
        println("WORKDIR = ${WORKDIR}")

        conda.install()

        PATH = "${WORKDIR}/miniconda/bin:${PATH}"
        def cpkgs = "conda=${CONDA_VERSION}"
        def pkg_list = aux_packages.replaceAll('\n', ' ')

        withEnv(["HOME=${HOME}", "PATH=${PATH}"]) {
            sh "conda install --quiet --yes ${cpkgs}"

            // Generate spec files
            sh "${WORKDIR}/mktestenv.sh -n ${delivery_name} -p 3.5 ${pkg_list}"
            sh "${WORKDIR}/mktestenv.sh -n ${delivery_name} -p 3.6 ${pkg_list}"
       
            // Make spec files available to master node. 
            stash name: "spec-stash-${label}", includes: "hstdp*.txt"
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
        sh "pwd; ls -al"
        sh "cp -r ${WORKSPACE}@script/*.sh ."
        stash name: "script", includes: "*.sh"
        sh "ls -al"
        parallel(
            Linux: { gen_specfiles('RHEL-6') },
            MacOS: { gen_specfiles('OSX-10.11') }
        )
    }

    stage('archive') {
        // Retrieve the spec files from the nodes where they were created.
        unstash "spec-stash-RHEL-6"
        unstash "spec-stash-OSX-10.11"
        archive "hstdp*txt"
    }
}
