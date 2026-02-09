# Mallproject for Rimfrost maskinell regel

Det här är ett mall projekt för att skapa en regel i Rimfrost-projektet.

En regel är mikrotjänst baserad på [Quarkus](https://quarkus.io/) och [Kogito](https://kogito.kie.org/)
för att producera ett beslut baserat på olika parametrar som antingen är givna eller samlas in under körning.

Denna mall lämpar sig för maskinella (automatiska) regler 
som producerar ett beslut utan interaktion med handläggare.

För regler som kräver interaktion med handläggare, se template
projektet för manuell regel.

## Minimum konfiguration

Projektet förväntar sig att jdk (java version 21 eller högre), 
docker och maven är installerat på systemet samt att 
miljövariablerna **GITHUB_ACTOR** och **GITHUB_TOKEN** är 
konfigurerade.

Notera att det GITHUB token som används förväntas ha repo access 
konfigurerad för att kunna hämta vissa projekt beroenden. 

## Bygg projektet

`./mvnw -s settings.xml clean verify`.

## Bygg docker image för lokal testning

`./mvnw -s settings.xml clean package`

## Github workflows

Två github workflows är inkluderade i projektet, maven-ci och maven-release.

maven-release skapar som del av sitt flöde en docker image.
Den publiseras till försäkringskassans [repository](https://github.com/Forsakringskassan/repository).

## Exempel implementation
Se [rimfrost-regel-rtf-maskinell](https://github.com/Forsakringskassan/rimfrost-regel-rtf-maskinell) för en färdig implementation av en maskinell regel.
