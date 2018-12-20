#!/usr/bin/env bash

# Create a conda environment specification file for a pipeline
# delivery. Automatically number the iteration based on spec
# files that already exist in the astroconda-releases repository.
# Automatically base iterations on the spec files produced by
# any existing previous iteration.
# Optionally allow for finalization of a spec file release.

set -e

releases_repo="https://github.com/astroconda/astroconda-releases"
pub_channel="http://ssb.stsci.edu/astroconda"
dev_channel="http://ssb.stsci.edu/astroconda-dev"

os="linux"
if [[ "$(uname)" == "Darwin" ]];
then
    os="osx"
fi

pkgs=""

scriptname=$(basename $0)

# Display help text when no arguments are supplied.
if [[ $# -eq 0 ]];
then
    printf "
Usage: ${scriptname} [-n] [-p] [-i] <packages...>

Produce a conda spec file for the next needed pipeline delivery iteration.
Iterations within a given release name build upon one another, such that
01 = 00 + any necessary changes.

-d --deliverable Name of deliverable (i.e. hstdp) (required)
-n --name        Name of release (i.e. 2018.1) (required)
-p --python      Python verison to use (i.e. 3.5, 3.6) (required)
-m --metapackage Metapackage base to use (i.e. stsci-hst) (required)
-f --final       Designate the environment to capture as a final delivery
                 environment. (optional)

   Positional arguments
<packages>
 A list of packages & versions of the form:
 <conda_package_name>=<version_string>, ...

"
    exit 0
fi

while (( "${#}" )); do
    case "${1}" in
        -d|--deliverable)
            deliverable=${2}
            shift 2
            ;;
	-n|--name)
	    name=${2}
	    shift 2
	    ;;
	-p|--python)
	    pyver=${2}
	    shift 2
	    ;;
        -m|--metapackage)
            metapackage=${2}
            shift 2
            ;;
	-f|--final)
	    iter="final"
	    shift
	    ;;
	--) # end argument parsing
	    shift
	    break
	    ;;
	-*|--*) # unsupported flags
	    echo "Error: Unsupported flag ${1}" >&2
	    exit 1
	    ;;
	*)  # positional arguments
	    pkgs="${pkgs} ${1}"
	    shift
	    ;;
    esac
done

eval set -- "${pkgs}"

if [[ -z "$deliverable" ]]; then
    echo "Must supply a deliverable name with -d or --deliverable."
    exit 1
fi

if [[ -z "$name" ]]; then
    echo "Must supply a name with -n or --name."
    exit 1
fi


if [[ -z "$pyver" ]]; then
    echo "Must supply a python version with -p or --python."
    exit 1
fi

if [[ -z "$metapackage" ]]; then
    echo "Must supply a conda metapackage with -m or --metapackage."
    exit 1
fi

# Compose short form of python version for use in file name.
pyver_S=${pyver//./}

# Clone the releases git repository if the iteration is not "FINAL"
# so the release iteration can be determined automatically.
if [[ $iter != "final" ]]; then
    # Clone pipeline releases repository
    clonedir=$(mktemp -d)
    git clone $releases_repo $clonedir

    deliverable_dir=${clonedir}/${deliverable}
    # If the name subdir exists, get the list of linux spec files from it.
    if [[ -d "${deliverable_dir}/${name}" ]]; then
	calculate_iter=true
    else
	echo "Releases subdir ${name} does not exist."
        echo "This will be iteration 00."
	iter="00"
    fi
fi

# If a release dir already exists, calculate the iteration value from its contents.
if [[ $calculate_iter == true ]]; then
    find ${deliverable_dir} -type d -name ${name}/dev
    if [[ ${?} -eq 0 ]]; then
        dev_specs=$(ls -1 ${deliverable_dir}/${name}/dev/${deliverable}*${os}*${pyver_S}*.txt)
    fi

    latest_iter=-1
    # Determine the highest iteration found for the release name
    for dev_spec in ${dev_specs}; do
        #echo $dev_spec
        fname=${dev_spec##*/}
        fname=${fname%.*}
        fname=$(echo ${fname} | cut -d '-' -f 4)
        file_iter=${fname##*.}
        if [[ $file_iter -gt $latest_iter ]]; then
        latest_iter=$file_iter
        # Spec file on which to base the new environment.
        latest_spec=${dev_spec}
        fi
    done

    echo "latest_spec=$latest_spec"

    # Increment latest iteration to reflect new file to create.
    (( latest_iter += 1 ))
    iter=$(printf %02d ${latest_iter})
fi

env_name="${deliverable}-${name}-${os}-py${pyver_S}.${iter}"
if [[ $latest_spec ]]; then
    echo "Creating conda environment ${env_name} from existing spec file..."
    conda create -y -q -n ${env_name} --file ${latest_spec}
else
    echo "Creating conda environment ${env_name} from ${metapackage} metapackage..."
    conda create -y -q -n ${env_name} -c ${pub_channel} -c defaults python=${pyver} ${metapackage}
fi

# Install any additional packages that were specified on the command line.
# Pull each from the appropriate astroconda channel depending on where they reside.
#  - versions with 'dev' in them come from the -dev channel
#  - others come from the public channel
source activate $env_name
printf "Packages:\n"
for pkg in $pkgs; do
    if [[ $pkg == *"dev"* ]]; then
	echo "${pkg} : This is a development package"
	channel=$dev_channel
    else
	echo "${pkg} : This is a public package"
	channel=$pub_channel
    fi
    conda install -y -q -c $channel -c defaults $pkg
done

printf "Writing spec file: ${env_name}.txt...  "
conda list --explicit > "${env_name}.txt"

# Clean up.
rm -rf $clonedir

printf "Done.\n"
