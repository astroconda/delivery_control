#!/usr/bin/env bash
# Accepts a list of <package>=<version> specifications used
# to convert a fully stable PUBLIC conda environment into one
# that reflects exactly what needs to be tested before
# delivering an HSTDP build to DMS.

set -e

pub_channel="http://ssb.stsci.edu/astroconda"
dev_channel="http://ssb.stsci.edu/astroconda-dev"

OS="linux"
if [[ "$(uname)" == "Darwin" ]];
then
    OS="osx"
fi

ARGS=""

scriptname=$(basename $0)

# Display help text when no arguments are supplied.
if [[ $# -eq 0 ]];
then
    printf "
Usage: ${scriptname} [-n] [-p] [-i] packages...

-n --hstdp-name  Name of HSTDP release (2018.1 etc)
-p --python      Python verison to use (3.5 etc)
-i --iteration   Delivery iteration number (0, 1...)

 Positional arguments
 A list of packages & versions of the form:
 <conda_package_name>=<version_string>, <>=<>, ...

"
    exit 0
fi

while (( "${#}" )); do
    case "${1}" in
	-n|--hstdp-name)
	    HSTDP_NAME=${2}
	    shift 2
	    ;;
	-p|--python)
	    PYVER=${2}
	    PYVER_S=${PYVER//./}
	    shift 2
	    ;;
	-i|--iteration)
	    case ${2} in
		''|*[!0-9]*) ITER=${2} ;;
		*) ITER=$(printf %02d ${2}) ;;
	    esac
	    shift 2
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
	    ARGS="${ARGS} ${1}"
	    shift
	    ;;
    esac
done

eval set -- "${ARGS}"

env_name="hstdp-${HSTDP_NAME}-${OS}-py${PYVER_S}.${ITER}"
conda create -y -q -n ${env_name} -c ${pub_channel} -c defaults python=${PYVER} stsci-hst

source activate ${env_name}
if [[ -n "$ARGS" ]];
then
    conda install -y -q -c ${dev_channel} -c defaults ${ARGS}
fi

printf "Writing spec file: ${env_name}.txt...  "
conda list --explicit > "${env_name}.txt"
printf "Done.\n"
