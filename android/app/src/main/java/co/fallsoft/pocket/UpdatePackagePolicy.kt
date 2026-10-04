package co.fallsoft.pocket

internal object UpdatePackagePolicy {
    const val MAX_BYTES=80L*1024*1024
    fun validate(version:Long,currentVersion:Long,size:Long,sha256:String,signer:String,installedSigner:String,path:String,packageName:String,currentPackage:String){
        require(version>currentVersion){"Update is not newer"}
        require(size in 1..MAX_BYTES){"Invalid update size"}
        require(sha256.matches(Regex("[a-f0-9]{64}"))){"Invalid update checksum"}
        require(signer==installedSigner&&signer.matches(Regex("[a-f0-9]{64}"))){"Update signer does not match"}
        require(path=="/api/app/update/apk"){"Invalid update endpoint"}
        require(packageName==currentPackage){"Update package does not match"}
    }
    fun validateArchive(packageName:String,expectedPackage:String,version:Long,expectedVersion:Long,signers:Set<String>,expectedSigner:String){
        require(packageName==expectedPackage&&version==expectedVersion&&signers==setOf(expectedSigner)){"Downloaded package does not match its release"}
    }
}
