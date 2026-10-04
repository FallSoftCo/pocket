package co.fallsoft.pocket

/** Readiness belongs to a verified artifact, independently of a subsequent network check. */
internal object UpdateStagePolicy {
    fun ready(version:Long,installed:Long,verified:Boolean,exists:Boolean,bytes:Long,expectedBytes:Long,signer:String,installedSigner:String)=
        verified&&exists&&version>installed&&bytes==expectedBytes&&expectedBytes>0&&signer==installedSigner
    fun replace(stagedVersion:Long,candidateVersion:Long)=candidateVersion>stagedVersion
}
