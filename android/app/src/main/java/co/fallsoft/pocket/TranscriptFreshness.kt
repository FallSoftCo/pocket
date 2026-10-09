package co.fallsoft.pocket

/** Inventory timestamps alone cannot invalidate a transcript or create a refresh loop. */
internal fun transcriptPresentationChanged(oldPreview:String?,oldKind:String?,oldStatus:String?,preview:String?,kind:String?,status:String?)=
    oldPreview!=preview||oldKind!=kind||oldStatus!=status

/** A fresh authoritative read can replace a cached streamed row whose server counter reset.
 * A row replaced by live output during that read keeps its version and wins reconciliation. */
internal fun <T:Any> authoritativeSnapshotBase(current:List<T>,baseline:Map<String,T>,incomingIds:Set<String>,id:(T)->String,reset:(T)->T)=
    current.map{row->if(id(row) in incomingIds&&baseline[id(row)]===row)reset(row) else row}
