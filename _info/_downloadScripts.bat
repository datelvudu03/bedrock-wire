ECHO OFF
ECHO Stazeni aktualnich skriptu


SET GROUP_ID=cz.syntea.maven.scripts
SET ARTIFACT_ID=syntea-maven-scripts-info
SET VERSION=RELEASE
SET CLASSIFIER=scripts
SET TYPE=zip
SET DESTINATION_DIRECTORY=.

DEL /q ~*
DEL /q _readme.txt
CALL mvn -ff com.googlecode.maven-download-plugin:maven-download-plugin:0.1:artifact -DgroupId=%GROUP_ID% -DartifactId=%ARTIFACT_ID% -Dversion=%VERSION% -Dclassifier=%CLASSIFIER% -Dtype=%TYPE% -DoutputDirectory=%DESTINATION_DIRECTORY%
RENAME %ARTIFACT_ID%-*-%CLASSIFIER%.zip %ARTIFACT_ID%-%VERSION%-%CLASSIFIER%.zip
CALL mvn -ff cz.syntea.maven.plugin:syntea-maven-plugin-unzip:1.1:unzip -DsrcArchive=%ARTIFACT_ID%-%VERSION%-%CLASSIFIER%.zip -DdestDir=%DESTINATION_DIRECTORY%
DEL /q *.zip
pause