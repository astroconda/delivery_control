// Parameters made available from Jenkins job configuration:
//   CONDA_INSTALLER_VERSION
//   CONDA_VERSION
//   aux_packages

this.py_maj_version = "3"
// The conda installer script to use for various <OS><py_version> combinations.
this.conda_installers  = ["Linux-py2":"Miniconda2-${CONDA_INSTALLER_VERSION}-Linux-x86_64.sh",
                          "Linux-py3":"Miniconda3-${CONDA_INSTALLER_VERSION}-Linux-x86_64.sh",
                          "MacOSX-py2":"Miniconda2-${CONDA_INSTALLER_VERSION}-MacOSX-x86_64.sh",
                          "MacOSX-py3":"Miniconda3-${CONDA_INSTALLER_VERSION}-MacOSX-x86_64.sh"]

this.CONDA_BASE_URL = "https://repo.continuum.io/miniconda"
// TODO: Consolidate in shared lib for convenience.
//if (this.CONDA_BASE_URL[-1..-1] == "/") {
//    this.CONDA_BASE_URL = [0..-2]
//}


def gen_specfiles(label) {
    
    node(label) {
    
        env.PYTHONPATH = ""
        // Make the log a bit more deterministic
        env.PYTHONUNBUFFERED = "true"
        def WORKDIR=pwd()
    
        // Delete any existing job workspace directory contents.
        // The directory deleted is the one named after the jenkins pipeline job.
        deleteDir()

        def OSname = null
        def uname = sh(script: "uname", returnStdout: true).trim()
        if (uname == "Darwin") {
            OSname = "MacOSX"
            println("OSname=${OSname}")
        }
        if (uname == "Linux") {
            OSname = uname
            println("OSname=${OSname}")
        }
        assert uname != null
        
        // Provide an isolated home directory unique to this build.
        sh "mkdir home"
        def HOME = "${WORKDIR}/home"
        
        // Check for the availability of a download tool and then use it
        // to get the conda installer.
        def dl_cmds = ["curl -OSs",
                       "wget --no-verbose --server-response --no-check-certificate"]
        def dl_cmd = null
        def stat1 = 999
        for (cmd in dl_cmds) {
            stat1 = sh(script: "which ${cmd.tokenize()[0]}", returnStatus: true)
            if( stat1 == 0 ) {
                dl_cmd = cmd
                break
            }
        }
        if (stat1 != 0) {
            println("Could not find a download tool. Unable to proceed.")
            sh "false"
        }
        
        def conda_install_dir = "${WORKDIR}/miniconda"
        def conda_installer =
            this.conda_installers["${OSname}-py${this.py_maj_version}"]
        dl_cmd = dl_cmd + " ${CONDA_BASE_URL}/${conda_installer}"
        sh dl_cmd    
        
        // Install specific version of miniconda
        sh "bash ./${conda_installer} -b -p ${conda_install_dir}"
        PATH = "${conda_install_dir}/bin:${PATH}"
        def cpkgs = "conda=${CONDA_VERSION}"
        
        def pkg_list = aux_packages.replaceAll('\n', ' ')
        withEnv(["PATH=${PATH}"]) {
            sh "conda install --quiet --yes ${cpkgs}"

            // Generate spec files
            sh "./mktestenv.sh -n 2018.1 -p 3.5 -i 1 ${pkg_list}"
            sh "./mktestenv.sh -n 2018.1 -p 3.6 -i 1 ${pkg_list}"
       
            // Make spec files available to master node. 
            stash name: "spec-stash-${OSname}", includes: "hstdp*.txt"
        }
    }
}

// Run the above function for each platform in parallel.
stage('create specfiles') {
    parallel( 
        Linux: { gen_specfiles('RHEL-6') },
        MacOS: { gen_specfiles('OSX-10.11') }
    )
}

node('boyle.stsci.edu') {
  stage('archive') {
    // Retrieve the spec files from the nodes where they were created.
    unstash "spec-stash-Linux"
    unstash "spec-stash-MacOSX"
    archive "hstdp*txt"
  }
}
