package co.fallsoft.pocket

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import java.util.Locale

/** Vocabulary changes inside teacher-authored target syntax; canonical source is never rewritten. */
internal data class ImmersionGrammarSlot(val target:String,val support:String)
internal fun immersionGrammarSlots(span:ImmersionSpan):List<ImmersionGrammarSlot>? {
    if(!alignedTargetValid(span))return null
    return span.targetSegments.map { segment ->
        // Keep target syntax and lexical positions, while every taught word
        // can reveal its contextual meaning. Whitespace/punctuation retain ink.
        ImmersionGrammarSlot(segment.target,segment.meaning.ifBlank{segment.target})
    }
}
internal fun immersionGrammarSupport(span:ImmersionSpan):String =
    immersionGrammarSlots(span)?.joinToString(""){it.support} ?: span.source

/** Both forms use the same lexical IDs. Spaces remain normal native paragraph boundaries. */
internal fun immersionGrammarForm(span:ImmersionSpan,support:Boolean,id:String):AnnotatedString? {
    val slots=immersionGrammarSlots(span)?:return null
    return buildAnnotatedString {
        slots.forEachIndexed { index,slot ->
            val start=length
            append(if(support)slot.support else slot.target)
            if(length>start&&slot.target.isNotBlank())
                addStringAnnotation("immersion-reserve","$id/lexical:$index",start,length)
        }
    }
}

/** Build a whole control from validated phrase anchors; untouched content retains its exact position. */
internal fun immersionGrammarPlanForm(plan:ImmersionPresentation,support:Boolean):AnnotatedString? {
    if(contextualHybridText(plan.source,plan.spans)!=plan.text||plan.spans.any{!alignedTargetValid(it)})return null
    var cursor=0
    return buildAnnotatedString {
        plan.spans.forEach{span->
            append(plan.source.substring(cursor,span.start))
            append(immersionGrammarForm(span,support,"${span.start}:${span.end}")?:return null)
            cursor=span.end
        }
        append(plan.source.substring(cursor))
    }
}

/** Expand phrase reservations using the original validated plan, not a mutated cycling span. */
internal fun immersionGrammarParagraph(content:AnnotatedString,plan:ImmersionPresentation):AnnotatedString {
    val replacements=content.getStringAnnotations("immersion-reserve",0,content.length).mapNotNull { range ->
        val span=plan.spans.firstOrNull{range.item=="${it.start}:${it.end}"}?:return@mapNotNull null
        val value=content.subSequence(range.start,range.end).text
        val support=immersionGrammarSupport(span)
        val form=when(value){
            span.target->immersionGrammarForm(span,false,range.item)
            support->immersionGrammarForm(span,true,range.item)
            else->null
        }?:return@mapNotNull null
        Triple(range.start,range.end,form)
    }
    return immersionAnnotatedParagraph(content,replacements)?:content
}

/** Authored UI pairs are exact contextual units, never a global word replacement dictionary. */
internal fun authoredImmersionScaffold(source:String,units:List<Pair<String,String>>):ImmersionSpan {
    var at=0
    val segments=units.map { (target,support) ->
        ImmersionTargetSegment(at,at+target.length,target,support,
            if(target.isBlank())"separator" else "other").also{at+=target.length}
    }
    return ImmersionSpan(0,source.length,source,units.joinToString(""){it.first},targetSegments=segments)
}
internal object InterfaceGrammarScaffolds {
    // Authored equivalents retain Italian order in the vocabulary support phase.
    private val rows=mapOf(
        "NextComp server address" to "Indirizzo|Address del|of~the server NextComp",
        "Runs" to "Esecuzioni|Runs",
        "Collapse runs" to "Comprimi|Collapse le|the esecuzioni|runs",
        "One-time pairing code" to "Codice|Code di|of associazione|pairing monouso|one-time",
        "Phone control needs Android access" to "Il|The controllo|control del|of~the telefono|phone richiede|needs accesso|access ad|to Android",
        "An update turned off NextComp’s Accessibility service." to "Un|A aggiornamento|update ha|has disattivato|disabled il|the servizio|service di|of accessibilità|accessibility di|of NextComp.",
        "Your updates." to "I|The tuoi|your aggiornamenti.|updates.",
        "Results, questions, and a way forward." to "Risultati,|Results, domande|questions e|and un|a modo|way di|of procedere.|forward.",
        "Open conversation  ↗" to "Apri|Open la|the conversazione|conversation ↗",
        "Translation unavailable · showing originals" to "Traduzione|Translation non|not disponibile|available · vengono|are mostrati|shown gli|the originali|originals",
        "Your connection." to "La|The tua|your connessione.|connection.",
        "Control this phone" to "Controlla|Control questo|this telefono|phone",
        "When both controls are enabled, Codex on this phone can inspect the foreground screen, take screenshots, tap, scroll, enter non-password text, and use Back, Home or Recents." to "Quando|When entrambi|both i|the controlli|controls sono|are abilitati,|enabled, Codex su|on questo|this telefono|phone può|can ispezionare|inspect la|the schermata|screen in primo~piano,|foreground, acquisire|take schermate,|screenshots, toccare,|tap, scorrere,|scroll, inserire|enter testo|text non~password|non-password e|and usare|use Indietro,|Back, Home o|or Recenti.|Recents.",
        "Screen structure and screenshots are sent to your signed-in Codex only when its phone tools are used. Password fields are never returned or filled." to "La|The struttura|structure dello|of~the schermo|screen e|and le|the schermate|screenshots vengono|are inviate|sent al|to~the tuo|your Codex autenticato|signed-in solo|only quando|when vengono|are usati|used i|the suoi|its strumenti|tools per|for il|the telefono.|phone. I|The campi|fields password non|not vengono|are mai|ever restituiti|returned né|nor compilati.|filled.",
        "Hear what happened." to "Ascolta|Hear cosa|what è|is successo.|happened.",
        "A different sound for results, questions, approvals and problems." to "Un|A suono|sound diverso|different per|for risultati,|results, domande,|questions, approvazioni|approvals e|and problemi.|problems.",
        "Generated/played counters cover notification and manual speech. Voice-mode connection time includes idle time. Playback does not prove audibility. Cancelled generation can still consume allowance; these are local activity counters, not billing totals." to "I|The contatori|counters di|of generazione/riproduzione|generation/playback coprono|cover la|the voce|speech delle|of~the notifiche|notifications e|and quella|that manuale.|manual. Il|The tempo|time di|of connessione|connection della|of~the modalità|mode vocale|voice include|includes il|the tempo|time inattivo.|idle. La|The riproduzione|Playback non|not dimostra|prove l’udibilità.|audibility. La|The generazione|Generation annullata|cancelled può|can ancora|still consumare|consume la|the quota;|allowance; questi|these sono|are contatori|counters locali|local di|of attività,|activity, non|not totali|totals di|of fatturazione.|billing.",
        "Speech uses media volume; quiet mode, notification mute and Do Not Disturb still silence it." to "La|The voce|Speech usa|uses il|the volume multimediale;|media; la|the modalità|mode silenziosa,|quiet, le|the notifiche|notifications mute|muted e|and Non~disturbare|Do~Not~Disturb la|the silenziano|silence comunque.|still.",
        "Remind me when I’m needed" to "Ricordami|Remind~me quando|when sono|are richiesto|needed",
        "Questions, approvals and problems only" to "Solo|Only domande,|questions, approvazioni|approvals e|and problemi|problems",
        "Up to three reminders: 5, 15, then 30 minutes apart. Dismiss, reply or resolve the request to stop. Later snoozes for 30 minutes." to "Fino~a|Up~to tre|three promemoria:|reminders: a|to 5, 15, poi|then 30 minuti|minutes di|of distanza.|apart. Chiudi,|Dismiss, rispondi|reply o|or risolvi|resolve la|the richiesta|request per|for fermarli.|stop. Più~tardi|Later rimanda|snoozes di|of 30 minuti.|minutes.",
        "Answer this request in the terminal." to "Rispondi|Answer a|to questa|this richiesta|request nel|in~the terminale.|terminal.",
        "Switch back to its pairing to check delivery. Removing this copy will not cancel work already submitted." to "Torna|Switch~back alla|to~the sua|its associazione|pairing per|for verificare|check la|the consegna.|delivery. Rimuovere|Removing questa|this copia|copy non|not annullerà|cancel il|the lavoro|work già|already inviato.|submitted.",
        "Codex may already have received it. We’ll check its saved receipt first and keep the same message identity. A missing receipt cannot guarantee that retrying won’t duplicate work." to "Codex potrebbe|might averlo|have~it già|already ricevuto.|received. Verificheremo|We’ll~check prima|first la|the ricevuta|receipt salvata|saved e|and manterremo|keep la|the stessa|same identità|identity del|of~the messaggio.|message. Una|A ricevuta|receipt mancante|missing non|not può|can garantire|guarantee che|that riprovare|retrying non|not duplichi|duplicate il|the lavoro.|work.",
        "Start where the work lives." to "Inizia|Start dove|where vive|lives il|the lavoro.|work.",
        "Describe the task, context and what done looks like." to "Descrivi|Describe l’incarico,|task, il|the contesto|context e|and cosa|what significa|means completato.|done.",
        "You’ll see progress here and get a notification when the task needs you or finishes." to "Vedrai|You’ll~see qui|here i|the progressi|progress e|and riceverai|receive una|a notifica|notification quando|when l’incarico|task richiede|needs il|the tuo|your intervento|input o|or termina.|finishes.",
        "The queue is empty." to "La|The coda|queue è|is vuota.|empty.",
        "This hides the conversation from recent tasks, pauses queued messages, and stops its notifications. Its history stays on the Codex host." to "Questo|This nasconde|hides la|the conversazione|conversation dagli|from~the incarichi|tasks recenti,|recent, mette~in~pausa|pauses i|the messaggi|messages in~coda|queued e|and ferma|stops le|the sue|its notifiche.|notifications. La|The sua|its cronologia|history rimane|stays sul|on~the computer|host Codex.",
        "Codex may already have received this message. Retrying can send it twice. Check the conversation first." to "Codex potrebbe|might aver|have già|already ricevuto|received questo|this messaggio.|message. Riprovare|Retrying può|can inviarlo|send due~volte.|twice. Controlla|Check prima|first la|the conversazione.|conversation.",
        "Excerpt · full content remains on the workstation" to "Estratto|Excerpt · il|the contenuto|content completo|full rimane|remains sul|on~the computer|workstation",
        "Output excerpt · full content remains on the workstation" to "Estratto|Excerpt dell’output|of~the~output · il|the contenuto|content completo|full rimane|remains sul|on~the computer|workstation",
        "Changes apply when the next turn starts. Steering keeps the running turn's settings." to "Le|The modifiche|Changes si|self applicano|apply quando|when inizia|starts il|the turno|turn successivo.|next. Orientare|Steering mantiene|keeps le|the impostazioni|settings del|of~the turno|turn in~corso.|running.",
        "No work updates yet" to "Ancora|Yet nessun|no aggiornamento|update sul|on~the lavoro|work",
        "Full permissions for new tasks" to "Permessi|Permissions completi|full per|for i|the nuovi|new incarichi|tasks",
        "Full device access. Commands run without approval prompts." to "Accesso|Access completo|full al|to~the dispositivo.|device. I|The comandi|commands vengono|are eseguiti|executed senza|without richieste|requests di|of approvazione.|approval.",
        "Applies to future tasks in this environment." to "Si|One applica|applies ai|to~the futuri|future incarichi|tasks in questo|this ambiente.|environment.",
        "Coordinator check-ins" to "Aggiornamenti|Updates del|of~the coordinatore|coordinator",
        "Status checks only; agent messages are manual" to "Solo|Only controlli|checks dello|of~the stato;|status; i|the messaggi|messages agli|to~the agenti|agents sono|are manuali|manual",
        "Work updates" to "Aggiornamenti|Updates del|of~the lavoro|work",
        "Work record" to "Registro|Record delle|of~the attività|activities",
        "Blackout" to "Schermo|Screen nero|black",
        "Blackout · keep screen awake" to "Schermo|Screen nero|black · mantieni|keep lo|the schermo|screen acceso|awake",
        "Add environment" to "Aggiungi|Add ambiente|environment",
        "Environment" to "Ambiente|Environment",
        "Environment name" to "Nome|Name dell’ambiente|dell’environment",
        "Codex credits" to "Crediti|Credits Codex",
        "Credit data unavailable" to "Dati|Data sui|on~the crediti|credits non|not disponibili|available",
        "Loading conversation…" to "Caricamento|Loading della|of~the conversazione…|conversation…",
        "Loading conversations" to "Caricamento|Loading delle|of~the conversazioni|conversations",
        "Conversations unavailable" to "Conversazioni|Conversations non|not disponibili|available",
        "Loading image…" to "Caricamento|Loading dell’immagine…|dell’image…",
        "Image unavailable" to "Immagine|Image non|not disponibile|available",
        "Loading" to "Caricamento|Loading",
        "App settings" to "Impostazioni|Settings dell’app|of~the~app",
        "Voice setup" to "Impostazioni|Settings vocali|voice",
        "Open source conversation" to "Apri|Open la|the conversazione|conversation di|of origine|origin",
        "Open parent task" to "Apri|Open l’incarico|the~task principale|parent",
        "Clear session" to "Svuota|Clear la|the conversazione|conversation",
        "Edit draft" to "Modifica|Edit la|the bozza|draft",
        "Remove saved copy" to "Rimuovi|Remove la|the copia|copy salvata|saved",
        "Queue message" to "Metti|Put il|the messaggio|message in coda|queue",
        "Send answer" to "Invia|Send la|the risposta|answer",
        "No pending requests" to "Nessuna|No richiesta|request in attesa|waiting",
        "Automatic language cycling" to "Alternanza|Cycling automatica|automatic delle|of~the lingue|languages",
        "Reduced motion · cycling without animation" to "Movimento|Motion ridotto|reduced · alternanza|cycling senza|without animazione|animation",
        "How much Italian?" to "Quanto|How~much italiano?|Italian?",
        "Talk" to "Parla|Speak",
        "Listen" to "Ascolta|Listen",
        "Settings" to "Impostazioni|Settings",
        "Backend" to "Motore|Backend",
        "App updates" to "Aggiornamenti|Updates dell’app|of~the~app",
        "Check for updates" to "Cerca|Check aggiornamenti|updates",
        "Italian immersion" to "Immersione|Immersion in italiano|Italian",
        "NextComp is up to date" to "NextComp è|is aggiornato|updated",
        "Full access" to "Accesso|Access completo|full",
        "Full permissions" to "Permessi|Permissions completi|full",
        "English support" to "Supporto|Support in inglese|English",
        "Language motion" to "Transizioni|Transitions di|of lingua|language",
        "Speech volume" to "Volume della|of~the voce|speech",
        "Answers & notes" to "Risposte|Answers e|and note|notes",
        "New task" to "Nuova|New attività|task",
        "New session" to "Nuova|New sessione|session",
        "New conversation" to "Nuova|New conversazione|conversation",
        "All sessions" to "Tutte|All le|the sessioni|sessions",
        "Show sessions" to "Mostra|Show sessioni|sessions",
        "Find sessions" to "Cerca|Find sessioni|sessions",
        "Search sessions" to "Cerca|Search sessioni|sessions",
        "Search conversations" to "Cerca|Search conversazioni|conversations",
        "Open conversation" to "Apri|Open conversazione|conversation",
        "Earlier messages" to "Messaggi|Messages precedenti|earlier",
        "Load earlier messages" to "Carica|Load messaggi|messages precedenti|earlier",
        "Load earlier" to "Carica|Load precedenti|earlier",
        "Reload conversation" to "Ricarica|Reload la|the conversazione|conversation",
        "Retry history" to "Ricarica|Reload la|the cronologia|history",
        "Loading history…" to "Caricamento|Loading della|of~the cronologia…|history…",
        "Notification settings" to "Impostazioni|Settings delle|of~the notifiche|notifications",
        "Send a test notification" to "Invia|Send una|a notifica|notification di|of prova|test",
        "Queued messages" to "Messaggi|Messages in coda|queue",
        "Resume queue" to "Riprendi|Resume la|the coda|queue",
        "Send message" to "Invia|Send messaggio|message",
        "Page up" to "Pagina|Page precedente|previous",
        "Page down" to "Pagina|Page successiva|next",
        "Stop & send" to "Ferma|Stop e|and invia|send",
        "Stop & Send" to "Ferma|Stop e|and invia|send",
        "Stop and send" to "Ferma|Stop e|and invia|send",
        "Clear saved speech" to "Svuota|Clear la|the voce|speech salvata|saved",
        "Clear all saved speech" to "Svuota|Clear tutte|all le|the letture|readings salvate|saved",
        "Hide controls" to "Nascondi|Hide controlli|controls",
        "Controls & usage" to "Controlli|Controls e|and utilizzo|usage",
        "Message coordinator" to "Scrivi|Write al|to~the coordinatore|coordinator",
        "Message Codex…" to "Scrivi|Write a|to Codex…",
        "Write a message" to "Scrivi|Write un|a messaggio|message",
        "Type a message" to "Scrivi|Type un|a messaggio|message",
        "Type your message" to "Scrivi|Type il|the tuo|your messaggio|message",
        "Plan mode" to "Modalità|Mode pianificazione|planning",
        "Build mode" to "Modalità|Mode esecuzione|execution",
        "Model, effort & mode" to "Modello,|Model, impegno|effort e|and modalità|mode",
        "Project folder" to "Cartella|Folder del|of~the progetto|project",
        "This phone" to "Questo|This telefono|phone",
        "Saved answer" to "Risposta|Answer salvata|saved",
        "Keep reply" to "Conserva|Keep risposta|reply",
        "Waiting for an answer" to "In attesa|waiting di|of una|a risposta|answer",
        "Queue" to "In coda|queue",
        "Queued" to "In coda|queued",
        "Listening" to "In ascolto|listening",
        "Speaking" to "Voce|Speech in corso|progress",
        "Sending" to "Invio|Sending in corso|progress",
        "Sending…" to "Invio|Sending in corso…|progress…",
        "Submitting…" to "Invio|Submitting in corso…|progress…",
        "Working" to "Al|To~the lavoro|work",
        "Paused" to "In pausa|pause",
        "Offline" to "Non|Not connesso|connected",
        "Codex offline" to "Codex non|not connesso|connected",
        "Failed" to "Non|Not riuscito|succeeded",
        "Later" to "Più|More tardi|later",
        "Check connection" to "Verifica|Check la|the connessione|connection",
        "Reconnect now" to "Riconnetti|Reconnect ora|now",
        "Retry now" to "Riprova|Retry ora|now",
        "Send now" to "Invia|Send ora|now",
        "Steer now" to "Orienta|Steer ora|now",
        "Install update" to "Installa|Install aggiornamento|update",
        "Checking update…" to "Ricerca|Checking aggiornamenti…|updates…",
        "Rename conversation" to "Rinomina|Rename conversazione|conversation",
        "Your answer" to "La|The tua|your risposta|answer",
        "Keep latest reply in notes" to "Conserva|Keep l’ultima|l’latest risposta|reply nelle|in~the note|notes",
        "Checking Losangelex…" to "Verifica|Checking di|of Losangelex…",
        "Connect Losangelex" to "Collega|Connect Losangelex",
        "Your NextComp host needs a Losangelex backend connection. Register its private endpoint and token file on the host, then reload." to "Il|The computer|host di|of NextComp richiede|needs una|a connessione|connection al|to~the motore|backend Losangelex. Registra|Register sul|on~the computer|host l’indirizzo|l’endpoint privato|private e|and il|the file del|of~the token, poi|then ricarica.|reload.",
        "Approval outcome is unconfirmed. Refresh its state before any further action." to "L’esito|L’outcome dell’approvazione|dell’approval non|not è|is confermato.|confirmed. Aggiorna|Refresh lo|the stato|state prima|before di|of qualsiasi|any altra|further azione.|action.",
        "Stale after" to "Inattivo|Stale dopo|after",
        "Change target" to "Cambia|Change destinazione|target",
        "Previous agents" to "Agenti|Agents precedenti|previous",
        "Next agents" to "Agenti|Agents successivi|next",
        "Check and retry this message?" to "Controllare|Check e|and riprovare|retry questo|this messaggio?|message?",
        "Check and retry" to "Controlla|Check e|and riprova|retry",
        "Pause recovery" to "Sospendi|Pause il|the recupero|recovery",
        "You asked" to "Hai|Have chiesto|asked",
        "Earlier work" to "Lavoro|Work precedente|earlier",
        "Recent team work" to "Lavoro|Work recente|recent del|of~the gruppo|team",
        "Recent voice work" to "Lavoro|Work vocale|voice recente|recent",
        "Follow-up in the same conversation" to "Proseguimento|Follow-up nella|in~the stessa|same conversazione|conversation",
        "Collected automatically from your requests and the resulting work. Follow-ups stay linked to their conversation." to "Raccolto|Collected automaticamente|automatically dalle|from~the tue|your richieste|requests e|and dal|from~the lavoro|work svolto.|performed. I|The proseguimenti|follow-ups restano|stay collegati|linked alla|to~the loro|their conversazione.|conversation.",
        "A completed turn records what the agent returned; it does not prove delivery or satisfaction." to "Un|A turno|turn completato|completed registra|records ciò|that che|that l’agente|the~agent ha|has restituito;|returned; non|not dimostra|proves la|the consegna|delivery o|or la|the soddisfazione.|satisfaction.",
        "Your work will appear here automatically as you use NextComp. Earlier followed conversations are filled in gradually." to "Il|The tuo|your lavoro|work apparirà|will~appear qui|here automaticamente|automatically mentre|while usi|use NextComp. Le|The conversazioni|conversations precedenti|earlier seguite|followed vengono|are aggiunte|added gradualmente.|gradually.",
        "This agent reports to its parent task. Review its results here and open the parent to send guidance. Direct replies to this agent are unavailable." to "Questo|This agente|agent risponde|reports all’incarico|all’task principale.|parent. Esamina|Review qui|here i|the risultati|results e|and apri|open l’incarico|the~task principale|parent per|for inviare|send indicazioni.|guidance. Le|The risposte|replies dirette|direct a|to questo|this agente|agent non|not sono|are disponibili.|available.",
        "Tap the black screen to return. Voice keeps working; Home and Lock remain available." to "Tocca|Tap lo|the schermo|screen nero|black per|for tornare.|return. La|The voce|voice continua|continues a|to funzionare;|work; Home e|and il|the blocco|lock restano|remain disponibili.|available.",
        "Updating messages" to "Aggiornamento|Updating messaggi|messages",
        "Observed balance decrease" to "Riduzione|Decrease del|of~the saldo|balance osservata|observed",
        "Across this account; not lifetime or per-session spending" to "Per|For questo|this account; non|not è|is la|the spesa|spending totale|lifetime né|nor per|for conversazione|session",
        "Balance increased · comparison restarted" to "Saldo|Balance aumentato|increased · confronto|comparison ricominciato|restarted",
        "Waiting for comparable balance observations" to "In attesa|waiting di|of rilevazioni|observations confrontabili|comparable del|of~the saldo|balance",
        "Provider reports a spending or account limit" to "Il|The servizio|provider segnala|reports un|a limite|limit di|of spesa|spending o|or dell’account|of~the~account",
        "Continuation status unavailable" to "Stato|Status della|of~the continuazione|continuation non|not disponibile|available",
        "Included allowance exhausted · existing-credit continuation available" to "Quota|Allowance inclusa|included esaurita|exhausted · continuazione|continuation con|with i|the crediti|credits disponibili|available",
        "Included allowance exhausted · credits present; continuation unverified" to "Quota|Allowance inclusa|included esaurita|exhausted · crediti|credits presenti;|present; continuazione|continuation non|not verificata|verified",
        "Included allowance exhausted · no available credits reported" to "Quota|Allowance inclusa|included esaurita|exhausted · nessun|no credito|credit disponibile|available segnalato|reported",
        "Included allowance remains" to "Quota|Allowance inclusa|included ancora|still disponibile|available",
        "Approve once" to "Approva|Approve una|a volta|time",
        "Reload backends" to "Ricarica|Reload i|the motori|backends",
        "Use Codex" to "Usa|Use Codex",
        "Close image" to "Chiudi|Close l’immagine|l’image",
        "New team task" to "Nuovo|New incarico|task al|to~the gruppo|team",
        "Task name" to "Nome|Name dell’incarico|dell’task",
        "What should the team do?" to "Che|What cosa|what deve|must fare|do il|the gruppo?|team?",
        "Retry saved task" to "Riprova|Retry l’incarico|the~task salvato|saved",
        "Retry saved answer" to "Riprova|Retry la|the risposta|answer salvata|saved",
        "Open request" to "Apri|Open la|the richiesta|request",
        "Waiting for fresh usage" to "In attesa|waiting dei|of~the dati|data di|of utilizzo|usage aggiornati|fresh",
        "Important" to "In evidenza|focus",
        "Unsave" to "Rimuovi|Remove dai|from~the salvati|saved",
        "Needs you" to "Serve|Needs il|the tuo|your intervento|input",
        "Your turn" to "Tocca|Turn a|to te|you",
        "Checking" to "Verifica|Checking in corso|progress",
        "Shown in conversation above" to "Mostrato|Shown nella|in~the conversazione|conversation qui|here sopra|above",
        "Connect Codex" to "Collega|Connect Codex",
        "Later · 30m" to "Più|More tardi|later · 30 min",
        "Connect Codex on this phone" to "Collega|Connect Codex su|on questo|this telefono|phone",
        "Disconnect this phone" to "Scollega|Disconnect questo|this telefono|phone",
        "Enable NextComp in Accessibility" to "Abilita|Enable NextComp nelle|in~the impostazioni|settings di|of accessibilità|accessibility",
        "Speak messages" to "Leggi|Read i|the messaggi|messages ad|to alta|loud voce|voice",
        "Talk to Codex" to "Parla|Talk con|with Codex",
        "Answer pending question" to "Rispondi|Answer alla|to~the domanda|question in attesa|waiting",
        "Retained passage · source outside loaded history" to "Passaggio|Passage conservato|retained · origine|source fuori|outside dalla|from~the cronologia|history caricata|loaded",
        "Spoken update ·" to "Aggiornamento|Update vocale|spoken ·",
        "Surrounding conversation" to "Contesto|Context della|of~the conversazione|conversation",
        "No important updates yet" to "Ancora|Still nessun|no aggiornamento|update in evidenza|focus",
        "Archive conversation?" to "Archiviare|Archive la|the conversazione?|conversation?",
        "Send this message again?" to "Inviare|Send di|of nuovo|again questo|this messaggio?|message?",
        "Retry anyway" to "Riprova|Retry comunque|anyway",
        "Edit message" to "Modifica|Edit il|the messaggio|message",
        "Next-turn controls" to "Controlli|Controls per|for il|the prossimo|next turno|turn",
        "Message the team" to "Scrivi|Write al|to~the gruppo|team",
        "Retry saved command" to "Riprova|Retry il|the comando|command salvato|saved",
        "Starting…" to "Avvio…|Starting…",
        "Check task status" to "Verifica|Check lo|the stato|status dell’attività|dell’task",
        "Start task" to "Avvia|Start l’attività|the~task",
        "Unfollow" to "Smetti|Stop di|of seguire|following",
        "Loading…" to "Caricamento…|Loading…",
        "Load earlier activity" to "Carica|Load attività|activity precedenti|earlier",
        "Steer this task…" to "Orienta|Steer questa|this attività…|task…",
        "▶ Finished" to "▶ Completato|Finished",
        "▶ Question" to "▶ Domanda|Question",
        "▶ Problem" to "▶ Problema|Problem",
        "Allow once" to "Consenti|Allow una|a volta|time",
        "Send answers" to "Invia|Send risposte|answers",
        "Retry saved turn" to "Riprova|Retry il|the messaggio|message salvato|saved",
        "Check turn" to "Verifica|Check turno|turn",
        "Retry needed" to "Riprova|Retry",
        "Running a command" to "Esecuzione|Running di|of un|a comando|command",
        "Reading a file" to "Lettura|Reading di|of un|a file",
        "Editing a file" to "Modifica|Editing di|of un|a file",
        "Running tests" to "Esecuzione|Running dei|of~the test|tests",
        "Ask anything" to "Chiedi|Ask qualsiasi|any cosa|thing",
        "Steer running turn" to "Orienta|Steer il|the turno|turn",
        "What would you like Codex to do?" to "Che|What cosa|what vuoi|want far|make fare|do a|to Codex?",
        "Original / Italiano" to "Originale|Original / Italiano|Italian",
        "No speech was captured. Check microphone privacy, then tap the microphone to record again." to "Non|Not è|is stato|been registrato|recorded alcun|any parlato.|speech. Controlla|Check le|the impostazioni|settings di|of privacy del|of~the microfono,|microphone, poi|then tocca|tap il|the microfono|microphone per|for registrare|record di|of nuovo.|again.",
        "Start voice" to "Avvia|Start la|the voce|voice",
        "Start voice in this conversation" to "Parla|Speak in questa|this conversazione|conversation",
        "Stop and send recording" to "Ferma|Stop e|and invia|send la|the registrazione|recording",
        "Your message" to "Il|The tuo|your messaggio|message",
        "Codex message" to "Messaggio|Message di|of Codex",
        "Text or talk to coordinate your sessions." to "Scrivi|Write o|or parla|talk per|for coordinare|coordinate le|the tue|your sessioni.|sessions.",
        "Coordinator chat" to "Chat del|of~the coordinatore|coordinator",
        "Coordinator keyboard" to "Tastiera|Keyboard del|of~the coordinatore|coordinator",
        "Send coordinator message" to "Invia|Send al|to~the coordinatore|coordinator",
        "Starting microphone" to "Avvio|Starting del|of~the microfono|microphone",
        "Finishing recording" to "Chiusura|Finishing della|of~the registrazione|recording",
        "Preparing speech" to "Preparazione|Preparing della|of~the voce|speech",
        "Read full spoken passage" to "Leggi|Read il|the brano|passage completo|full",
        "Dismiss captions" to "Chiudi|Dismiss i|the sottotitoli|captions",
        "NextComp spoken update" to "Aggiornamento|Update vocale|spoken di|of NextComp",
        "NextComp voice is on" to "La|The voce|voice di|of NextComp è|is attiva|on",
        "Unlock to read" to "Sblocca|Unlock per|for leggere|read",
        "Voice mode" to "Modalità|Mode vocale|voice",
        "End voice" to "Termina|End la|the voce|voice",
        "Volume down: talk · Volume up: pause or replay" to "Volume giù:|down: parla|talk · Volume su:|up: pausa|pause o|or riascolto|replay",
        "Spoken message controls" to "Controlli|Controls dei|of~the messaggi|messages vocali|spoken",
        "NextComp speech paused" to "Voce|Speech di|of NextComp in pausa|pause",
        "Listening to NextComp" to "Ascolto|Listening di|of NextComp",
        "NextComp spoken updates" to "Aggiornamenti|Updates vocali|spoken di|of NextComp",
        "NextComp update" to "Aggiornamento|Update di|of NextComp",
        "Your place is saved" to "Il|The punto|place di|of ascolto|listening è|is salvato|saved",
        "Pause any time" to "Puoi|Can mettere|put in pausa|pause in qualsiasi|any momento|time",
        "Saved for later" to "Salvato|Saved per|for dopo|later",
        "Waiting while other audio plays" to "In attesa|waiting che|that finisca|finishes l’altro|l’other audio",
        "Paused by you" to "Messo|Put in pausa|pause da|by te|you",
        "Ready when you are" to "Pronto|Ready quando|when vuoi|want",
        "Headphones disconnected" to "Cuffie|Headphones scollegate|disconnected",
        "Speech stalled · Tap Resume to retry" to "Voce|Speech bloccata|stalled · Tocca|Tap Riprendi|Resume per|for riprovare|retry",
        "Offline voice unavailable · Tap Resume to retry" to "Voce|Voice offline non|not disponibile|available · Tocca|Tap Riprendi|Resume per|for riprovare|retry",
        "Could not prepare speech · Tap Resume to retry" to "Preparazione|Preparation della|of~the voce|speech non|not riuscita|successful · Tocca|Tap Riprendi|Resume per|for riprovare|retry",
        "Profile changed · Your place is saved" to "Profilo|Profile cambiato|changed · Il|The punto|place di|of ascolto|listening è|is salvato|saved",
        "Waiting for sound to be enabled" to "In attesa|waiting dell’attivazione|dell’enabling dell’audio|of~the~audio",
        "Reconnect to your workstation to load the full message" to "Riconnettiti|Reconnect alla|to~the workstation per|for caricare|load il|the messaggio|message completo|full",
        "Install an offline English voice to listen" to "Installa|Install una|a voce|voice inglese|English offline per|for ascoltare|listen",
        "Paused for other audio" to "In pausa|pause per|for un|a altro|other audio",
        "Waiting for other audio to finish" to "In attesa|waiting che|that finisca|finishes l’altro|l’other audio",
        "Playback interrupted · Tap Resume to retry" to "Riproduzione|Playback interrotta|interrupted · Tocca|Tap Riprendi|Resume per|for riprovare|retry",
        "Allow microphone access to use voice." to "Consenti|Allow l’accesso|the~access al|to~the microfono|microphone per|for usare|use la|the voce.|voice.",
        "Open NextComp to start voice mode." to "Apri|Open NextComp per|for avviare|start la|the modalità|mode vocale.|voice.",
        "Microphone access is needed for voice mode. Enable it in Android app settings." to "La|The modalità|mode vocale|voice richiede|requires il|the microfono.|microphone. Attivalo|Enable-it nelle|in~the impostazioni|settings Android dell’app.|of~the~app.",
        "Microphone access was revoked. Enable it in Android app settings." to "L’accesso|The~access al|to~the microfono|microphone è|is stato|been revocato.|revoked. Attivalo|Enable-it nelle|in~the impostazioni|settings Android dell’app.|of~the~app.",
        "Another app is using audio. Try again when it finishes." to "Un’altra|Un’other app sta|is usando|using l’audio.|the~audio. Riprova|Retry quando|when ha|has finito.|finished.",
        "Another app is using audio. Retry when it finishes." to "Un’altra|Un’other app sta|is usando|using l’audio.|the~audio. Riprova|Retry quando|when ha|has finito.|finished.",
        "Recording interrupted by another app. Please repeat your turn." to "Registrazione|Recording interrotta|interrupted da|by un’altra|un’other app. Ripeti|Repeat il|the messaggio.|message.",
        "That device is not paired. Pair it in NextComp first." to "Il|The dispositivo|device non|not è|is abbinato.|paired. Abbinalo|Pair-it prima|first in NextComp.",
        "Unable to play speech. Press volume up to replay." to "Impossibile|Unable riprodurre|play la|the voce.|speech. Premi|Press volume su|up per|for riascoltare.|replay.",
        "Microphone stopped." to "Microfono|Microphone arrestato.|stopped.",
        "The voice turn stopped." to "Il|The messaggio|message vocale|voice si|self è|is interrotto.|stopped.",
        "Codex updates & replies" to "Aggiornamenti|Updates e|and risposte|replies di|of Codex",
        "Updates you request from Codex, questions, and replies to your phone messages." to "Aggiornamenti|Updates richiesti|requested a|to Codex, domande|questions e|and risposte|replies ai|to~the messaggi|messages dal|from~the telefono.|phone.",
        "Still needs your attention" to "Richiede|Needs ancora|still la|the tua|your attenzione|attention",
        "Your reply…" to "La|The tua|your risposta…|reply…",
        "Open task" to "Apri|Open l’attività|the~task",
        "Sending reply to Codex…" to "Invio|Sending della|of~the risposta|reply a|to Codex…",
        "Transcribing" to "Trascrizione|Transcribing",
        "Spoken" to "Ascoltato|Spoken",
        "Read" to "Leggi|Read",
        "Gradual" to "Graduale|Gradual",
        "Balanced" to "Bilanciato|Balanced",
        "Intense" to "Intenso|Intense",
        "All" to "Tutti|All",
        "Clear" to "Svuota|Clear",
        "Remove" to "Rimuovi|Remove",
        "Starting" to "Avvio|Starting",
        "Stopped" to "Interrotto|Stopped",
        "Room" to "Gruppo|Room",
        "Direct" to "Diretto|Direct",
        "Review" to "Esamina|Review",
        "Edit" to "Modifica|Edit",
        "Latest" to "Ultimi|Latest",
        "Tones" to "Suoni|Tones",
        "Labels" to "Etichette|Labels",
        "System" to "Sistema|System",
        "Finished" to "Completato|Finished",
        "Question" to "Domanda|Question",
        "Problem" to "Problema|Problem",
        "Activity" to "Attività|Activity",
        "Recent" to "Recenti|Recent",
        "Following" to "Seguiti|Following",
        "Archived" to "Archiviati|Archived",
        "Decline" to "Rifiuta|Decline",
        "Refresh" to "Aggiorna|Refresh",
        "Work" to "Lavoro|Work",
        "Mission" to "Missione|Mission",
        "Find" to "Cerca|Find",
        "Mic" to "Microfono|Mic",
        "Microphone" to "Microfono|Microphone",
        "Keyboard" to "Tastiera|Keyboard",
        "Back" to "Indietro|Back",
        "Stop" to "Ferma|Stop",
        "Send" to "Invia|Send",
        "Record" to "Registra|Record",
        "Processing" to "Elaborazione|Processing",
        "Recording" to "Registrazione|Recording",
        "Ready" to "Pronto|Ready",
        "Resume" to "Riprendi|Resume",
        "Pause" to "Pausa|Pause",
        "Replay" to "Riascolta|Replay",
        "Connected" to "Connesso|Connected",
        "Connecting" to "Connessione|Connecting",
        "Reconnecting" to "Riconnessione|Reconnecting",
        "On" to "Attivo|On",
        "Off" to "Disattivo|Off",
        "Notifications" to "Notifiche|Notifications",
        "Notification" to "Notifica|Notification",
        "Steer" to "Orienta|Steer",
        "Reply" to "Rispondi|Reply",
        "Answer" to "Rispondi|Answer",
        "Skip" to "Salta|Skip",
        "Retry" to "Riprova|Retry",
        "Cancel" to "Annulla|Cancel",
        "Save" to "Salva|Save",
        "Close" to "Chiudi|Close",
        "Done" to "Fatto|Done",
        "Dismiss" to "Chiudi|Dismiss",
        "Copy" to "Copia|Copy",
        "Keep" to "Conserva|Keep",
        "Rename" to "Rinomina|Rename",
        "Archive" to "Archivia|Archive",
        "Restore" to "Ripristina|Restore",
        "Model" to "Modello|Model",
        "Effort" to "Impegno|Effort",
        "Mode" to "Modalità|Mode",
        "Follow" to "Segui|Follow",
        "Coordinator" to "Coordinatore|Coordinator",
        "Thinking" to "Riflessione|Thinking",
        "Complete" to "Completato|Complete",
        "Completed" to "Completato|Completed",
        "Searching" to "Ricerca|Searching",
        "Message" to "Messaggio|Message",
        "Volume" to "Volume|Volume",
        "Usage" to "Utilizzo|Usage",
        "Details" to "Dettagli|Details",
        "Update" to "Aggiorna|Update",
        "Updates" to "Aggiornamenti|Updates",
        "Workstation" to "Computer|Workstation",
        "History" to "Cronologia|History",
        "Questions" to "Domande|Questions",
        "Rooms" to "Stanze|Rooms",
        "Find rooms" to "Trova|Find stanze|rooms",
        "No matching rooms" to "Nessuna|No stanza|room corrispondente|matching",
        "Close search" to "Chiudi|Close la|the ricerca|search",
        "Attention" to "Attenzione|Attention"
    )
    private fun target(row:String)=row.split(" ").joinToString(" "){it.substringBefore("|").replace("~"," ")}
    internal val translations:Map<String,String> = rows.mapValues{target(it.value)}
    private val normalizedRows=rows.mapKeys{it.key.lowercase(Locale.ROOT)}
    private val normalizedTranslations=translations.mapKeys{it.key.lowercase(Locale.ROOT)}
    private val literalSuffixes=listOf(
        "Background refresh: " to "Aggiornamento|Refresh in background:",
        "Resets " to "Ripristino|Resets",
        "Estimated run-out " to "Esaurimento|Run-out stimato|estimated",
        "Last known · " to "Ultimo|Last dato|data noto|known ·",
        "Checked · " to "Verificato|Checked ·"
    )
    fun italian(source:String):String? =
        normalizedTranslations[source.trim().lowercase(Locale.ROOT)]
            ?:literalSuffixes.firstOrNull{source.startsWith(it.first)}
                ?.let{target(it.second)+" "+source.removePrefix(it.first)}
    fun find(source:String,target:String):ImmersionSpan? {
        val suffix=literalSuffixes.firstOrNull{source.startsWith(it.first)}
        val row=normalizedRows[source.trim().lowercase(Locale.ROOT)]?:suffix?.second
        if(row==null)return null
        val units=mutableListOf<Pair<String,String>>()
        row.split(" ").forEachIndexed { index,value ->
            if(index>0)units+=" " to " "
            val parts=value.split("|",limit=2)
            units+=parts[0].replace("~"," ") to parts.getOrElse(1){parts[0]}.replace("~"," ")
        }
        if(suffix!=null){
            units+=" " to " "
            val literal=source.removePrefix(suffix.first)
            if(literal.isNotEmpty())units+=literal to literal
        }
        return authoredImmersionScaffold(source,units).takeIf{it.target==target}
    }
}
