# Sample Hyperledger Fabric cross organization demo using Java SDK
Requires Hyperledger Fabric network provided by the [Java SDK](https://github.com/hyperledger/fabric-sdk-java)

Main sample code is in [ /src/main/java/org/cr22rc/MultiDomainSample.java ](https://github.com/cr22rc/fabricSDKJavaMultiDomainSample/blob/master/src/main/java/org/cr22rc/MultiDomainSample.java)

Once Fabric network from SDK is running you can run this sample with:

`MAVEN_OPTS=-ea mvn  clean install exec:java -Dexec.mainClass="org.cr22rc.MultiDomainSample"`

## generating an applicaiton
`mvn package appassembler:assemble`

now run it:

`JAVA_OPTS=-ea ./target/appassembler/bin/app`
