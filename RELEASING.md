# Releasen naar Maven Central

Deze library wordt gepubliceerd naar Maven Central via het [Central Portal](https://central.sonatype.com) onder de namespace `nl.mijnoverheidzakelijk`.

## Eenmalig: project

Dit staat al geconfigureerd in de `pom.xml`:

- Verplichte POM-metadata: `name`, `description`, `url`, `licenses`, `developers`, `scm`
- Sources- en javadoc-jars, verplicht voor releases:
  - `maven-source-plugin` (attach-sources)
  - `dokka-maven-plugin` (javadocJar); `maven-javadoc-plugin` levert niets op bij Kotlin-code
- `central-publishing-maven-plugin` met `<publishingServerId>central</publishingServerId>`
- `distributionManagement > snapshotRepository` (id `central`) naar `https://central.sonatype.com/repository/maven-snapshots/`
- Profiel `release` met `maven-gpg-plugin`; releases moeten gesigned zijn, snapshots niet

## Eenmalig: gebruiker

1. Maak een account op central.sonatype.com.
   Let op: username/password, Google en GitHub zijn aparte accounts; kies er een en blijf daarbij.
2. Vraag toegang tot de namespace `nl.mijnoverheidzakelijk`, of registreer een nieuwe
   ([docs](https://central.sonatype.org/register/namespace/)).
3. Genereer een user token: View Account -> Generate User Token (kan verlopen!).
4. Maak een GPG-key aan en upload de publieke key naar een keyserver:

   ```
   gpg --full-generate-key
   gpg --keyserver keys.openpgp.org --send-keys <FINGERPRINT>
   ```

5. Zet credentials in `~/.m2/settings.xml` (Windows: `C:\Users\<naam>\.m2\settings.xml`):

   ```xml
   <settings xmlns="http://maven.apache.org/SETTINGS/1.1.0"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.1.0 https://maven.apache.org/xsd/settings-1.1.0.xsd">

       <servers>
           <server>
               <id>central</id>
               <username></username>
               <password></password>
           </server>
           <server>
               <id>gpg.passphrase</id>
               <passphrase></passphrase>
           </server>
       </servers>

       <profiles>
           <profile>
               <id>gpg</id>
               <properties>
                   <gpg.executable>gpg</gpg.executable>
                   <gpg.keyname></gpg.keyname>
               </properties>
           </profile>
       </profiles>

       <activeProfiles>
           <activeProfile>gpg</activeProfile>
       </activeProfiles>
   </settings>
   ```

   Kanttekening: de passphrase hoort in `<servers>` onder id `gpg.passphrase`, niet als
   property in een profiel. Daar wordt hij niet ontsleuteld en overschrijft hij de server-entry.

6. Versleutel de waarden optioneel met een master password in `~/.m2/settings-security.xml`:

   ```
   mvn --encrypt-master-password   # resultaat in <settingsSecurity><master> hieronder
   mvn --encrypt-password          # {...}-waarde voor password/passphrase in settings.xml
   ```

   ```xml
   <settingsSecurity>
       <master></master>
   </settingsSecurity>
   ```

## Snapshot deployen

1. De versie moet eindigen op `-SNAPSHOT`
2. `mvn clean deploy`
3. Afnemers moeten de snapshot-repo in hun eigen pom opnemen
   (`https://central.sonatype.com/repository/maven-snapshots/`, snapshots enabled)

## Release deployen

1. Zet de versie zonder `-SNAPSHOT`
2. `mvn clean deploy -Prelease`
3. Ga op central.sonatype.com naar Publish: de deployment wordt gevalideerd, klik daarna
   handmatig op Publish (autopublish kan aan via de plugin-config, staat nu uit)
4. Na publiceren duurt het ca. 15-30 minuten voordat het artifact op Central staat
5. Een gepubliceerde release is definitief: niet te overschrijven of verwijderen
6. Tag de release (`vX.Y.Z`) en zet main terug op de volgende `-SNAPSHOT`-versie
