Skripty pro maven na ziskaní informací.
Pro získání akruálních skriptù spustte:
_downloadScripts.bat
Stahuje se RELEASE verze, tj. nejvyssi uzavrena NON-SNAPSHOT verze,
z artefaktu cz.syntea.maven.scripts:syntea-maven-scripts-info

Jednotlivé skripty jsou pojmenovány ~*.bat
Pokud chcete vysledek automaticky zobrazit nastavte env promennou SYNTEA_MVN_INFO_VIEWER.
Pokud SYNTEA_MVN_INFO_VIEWER neni nastavena, nebo je none, pak se nic nezobrazi

Zpusob jak nastavit:
setx SYNTEA_MVN_INFO_VIEWER "none"  /m
setx SYNTEA_MVN_INFO_VIEWER "uedit64"  /m
setx SYNTEA_MVN_INFO_VIEWER "notepad"  /m
