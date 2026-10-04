package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class UpdatePackagePolicyTest {
    private val signer="a".repeat(64)
    private val hash="b".repeat(64)
    private fun manifest(version:Long=25,size:Long=7000000,digest:String=hash,signature:String=signer,path:String="/api/app/update/apk",pkg:String="co.fallsoft.pocket")=UpdatePackagePolicy.validate(version,24,size,digest,signature,signer,path,pkg,"co.fallsoft.pocket")
    private fun rejected(block:()->Unit){try{block();fail("Unsafe update accepted")}catch(_:IllegalArgumentException){}}
    @Test fun permitsOnlyNewerSameSignerSamePackageBoundedDownload(){manifest()}
    @Test fun rejectsStaleVersionWrongCertificateAndUntrustedEndpoints(){
        rejected{manifest(version=24)};rejected{manifest(version=23)}
        rejected{manifest(signature="c".repeat(64))}
        rejected{manifest(path="https://example.com/foreign.apk")}
        rejected{manifest(path="/api/app/update/apk?redirect=foreign")}
        rejected{manifest(pkg="other.app")}
    }
    @Test fun rejectsMalformedDigestAndOversizedOrEmptyDownloads(){
        rejected{manifest(digest="short")};rejected{manifest(digest="B".repeat(64))}
        rejected{manifest(size=0)};rejected{manifest(size=UpdatePackagePolicy.MAX_BYTES+1)}
    }
    @Test fun rejectsArchiveDisagreeingWithAuthenticatedMetadata(){
        UpdatePackagePolicy.validateArchive("co.fallsoft.pocket","co.fallsoft.pocket",25,25,setOf(signer),signer)
        rejected{UpdatePackagePolicy.validateArchive("other.app","co.fallsoft.pocket",25,25,setOf(signer),signer)}
        rejected{UpdatePackagePolicy.validateArchive("co.fallsoft.pocket","co.fallsoft.pocket",26,25,setOf(signer),signer)}
        rejected{UpdatePackagePolicy.validateArchive("co.fallsoft.pocket","co.fallsoft.pocket",25,25,setOf("c".repeat(64)),signer)}
        rejected{UpdatePackagePolicy.validateArchive("co.fallsoft.pocket","co.fallsoft.pocket",25,25,setOf(signer,"c".repeat(64)),signer)}
    }
}
