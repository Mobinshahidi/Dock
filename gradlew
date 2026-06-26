#!/bin/sh
#
# Gradle start up script for UN*X
#############################################################################
# Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS
# to pass JVM options to this script.
DEFAULT_JVM_OPTS='-Xmx64m -Xms64m'

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")

# Use the maximum available, or set MAX_FD != -1 to use that value.
MAX_FD="maximum"

warn() {
    echo "$*"
}

die() {
    echo
    echo "$*"
    echo
    exit 1
}

# OS specific support (must be 'true' or 'false').
cygwin=false
msys=false
darwin=false
nonstop=false
case "$(uname)" in
  CYGWIN* )
    cygwin=true
    ;;
  Darwin* )
    darwin=true
    ;;
  MINGW* )
    msys=true
    ;;
  NONSTOP* )
    nonstop=true
    ;;
esac

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        # IBM's JDK on AIX uses strange locations for the executables
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
fi

# Increase the maximum file descriptors if we can.
if [ "$cygwin" = "false" -a "$darwin" = "false" -a "$nonstop" = "false" ] ; then
    MAX_FD_LIMIT=$(ulimit -H -n)
    if [ $? -eq 0 ] ; then
        if [ "$MAX_FD" = "maximum" -o "$MAX_FD" = "max" ] ; then
            MAX_FD="$MAX_FD_LIMIT"
        fi
        ulimit -n $MAX_FD
        if [ $? -ne 0 ] ; then
            warn "Could not set maximum file descriptor limit: $MAX_FD"
        fi
    else
        warn "Could not query maximum file descriptor limit: $MAX_FD_LIMIT"
    fi
fi

# For Darwin, add options to specify how the application appears in the dock
if $darwin; then
    GRADLE_OPTS="$GRADLE_OPTS \"-Xdock:name=$APP_NAME\" \"-Xdock:icon=$APP_HOME/media/gradle.icns\""
fi

# For Cygwin or MSYS, switch paths to Windows format before running java
if [ "$cygwin" = "true" -o "$msys" = "true" ] ; then
    APP_HOME=$(cygpath --path --mixed "$APP_HOME")
    CLASSPATH=$(cygpath --path --mixed "$CLASSPATH")
    JAVACMD=$(cygpath --unix "$JAVACMD")
    # We build the pattern for arguments to be converted via cygpath
    ROOTDIRSRAW=$(find / -maxdepth 1 -name "cygdrive" -type d 2>/dev/null | head -1)
    if [ -n "$ROOTDIRSRAW" ] ; then
        ROOTDIRS=$(cygpath --path --unix "$ROOTDIRSRAW")
        for ROOTDIR in $ROOTDIRS ; do
            # Remove prefix /cygdrive/c from paths
            PATTERN="^$ROOTDIR([A-Z]):"
            # Replace with C:...
            CYGPATTERN="$PATTERN"
        done
    fi
fi

# Split up the JVM_OPTS And GRADLE_OPTS values into an array, following the shell quoting rules
# This is needed because we might have quoted strings that need to be preserved
function splitJvmOpts() {
    JVM_OPTS=()
    for arg in "$@" ; do
        JVM_OPTS+=("$arg")
    done
}

splitJvmOpts $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS

JVM_OPTS=("${JVM_OPTS[@]}" "-Dorg.gradle.appname=$APP_BASE_NAME")

exec "$JAVACMD" "${JVM_OPTS[@]}" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"