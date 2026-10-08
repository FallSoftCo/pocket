package co.fallsoft.pocket

/** Admission after an asynchronous SDP request, including cancellation and profile switches. */
internal fun nativeNegotiationCurrent(offerEpoch:Long,currentEpoch:Long,active:Boolean,hasTransport:Boolean)=
    offerEpoch==currentEpoch&&active&&hasTransport

/** A server ID alone cannot prove that the local WebRTC transport still exists. */
internal fun nativeTransportNeedsConnection(connectionId:String?,hasTransport:Boolean)=connectionId.isNullOrBlank()||!hasTransport
