#!/bin/bash

# If you get "UnsatisfiedLinkError: /tmp/usb4java... failed to map segment from shared object: Operation not permitted",
# add the following line to the command below:
# -Djava.io.tmpdir=~/tmpdir/

# If you're using AMD graphics cards on Linux with the latest open-source Mesa drivers, you might get an
# error about not finding a supported OpenGL profile, to fix please change the classpath line below to:
# -classpath "dist/jAER.jar:jars/*:jars/jogl_mesa_amd_fix/*:lib/*" \
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
#DIR=`dirname "$0`

# TODO: We must be able to provide the compiled OpenCV library files for windows, mac, and linux

java \
-classpath "$DIR/dist/jAER.jar:$DIR/jars/*:$DIR/lib/*" \
-Djava.library.path="$DIR/jars:$DIR/jars/opencv-native" \
-splash:images/SplashScreen.png \
-Dsun.java2d.uiScale=2.0 \
-Djava.util.logging.config.file="$DIR/conf/Logging.properties" -Dsun.java2d.noddraw=true -Dsun.java2d.opengl=false -D-Jsun.java2d.dpiaware=true \
net.sf.jaer.JAERViewer "$@"
