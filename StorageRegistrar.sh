#!/bin/bash
#Switch working directory regardless of where user invoked it.
#http://stackoverflow.com/questions/3349105/how-to-set-current-working-directory-to-the-directory-of-the-script
cd "$(dirname "$0")"
java -cp "lib/common/jar/*" storageRegistrar.StorageRegistrar config/StorageRegistrarConfig.yml
