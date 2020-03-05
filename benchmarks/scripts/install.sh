#!/usr/bin/env bash

# Install required packages (Ubuntu 18.04)
echo "Installing DFTRES dependencies";
sudo apt-get install make git openjdk-11-jdk-headless;

# Set java 11 as default
echo "Setting JDK 11 as default";
JAVA11=$(update-alternatives  --list java  | grep "java.*11*.openjdk");
JAVAC11=$(update-alternatives --list javac | grep "java.*11*.openjdk");
sudo update-alternatives --set java $JAVA11;
sudo update-alternatives --set javac $JAVAC11;

# Clone git repo
DIR="DFTRES";
echo "Cloning DFTRES git repo in $DIR";
git clone https://github.com/utwente-fmt/DFTRES.git $DIR -b qcomp2020;

# Build jar file
echo "Building DFTRES";
(cd $DIR && make jar)
if [ -f ${DIR}/DFTRES.jar ]; then
	echo "DFTRES.jar successfully built in ${DIR}/";
else
	echo "Failed to build DFTRES.jar  :(";
fi
	
exit 0;
