package co.fallsoft.pocket

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import java.util.Locale

/** Vocabulary changes inside teacher-authored target syntax; canonical source is never rewritten. */
internal data class ImmersionGrammarSlot(val target:String,val support:String)
internal fun immersionGrammarSlots(span:ImmersionSpan):List<ImmersionGrammarSlot>? {
    if(!alignedTargetValid(span))return null
    return span.targetSegments.map { segment ->
        val grammatical=segment.role in setOf("determiner","preposition","auxiliary","pronoun","conjunction","separator","punctuation") ||
            segment.relations.any{it.kind=="negates"}
        ImmersionGrammarSlot(segment.target,if(grammatical)segment.target else segment.meaning.ifBlank{segment.target})
    }
}
internal fun immersionGrammarSupport(span:ImmersionSpan):String =
    immersionGrammarSlots(span)?.joinToString(""){it.support} ?: span.target

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
    // Structure/function words remain Italian in the vocabulary support phase.
    private val rows=mapOf(
        "NextComp server address" to "Indirizzo|Address del server NextComp",
        "Runs" to "Esecuzioni|Runs",
        "Collapse runs" to "Comprimi|Collapse le esecuzioni|runs",
        "One-time pairing code" to "Codice|Code di associazione|pairing monouso|one-time",
        "Phone control needs Android access" to "Il controllo|control del telefono|phone richiede|needs accesso|access ad Android",
        "An update turned off NextComp’s Accessibility service." to "Un aggiornamento|update ha disattivato|disabled il servizio|service di accessibilità|accessibility di NextComp.",
        "Your updates." to "I tuoi aggiornamenti.|updates.",
        "Results, questions, and a way forward." to "Risultati,|Results, domande|questions e un modo|way di procedere.|forward.",
        "Open conversation  ↗" to "Apri|Open la conversazione|conversation ↗",
        "Translation unavailable · showing originals" to "Traduzione|Translation non disponibile|available · vengono mostrati|shown gli originali|originals",
        "Your connection." to "La tua connessione.|connection.",
        "Control this phone" to "Controlla|Control questo telefono|phone",
        "When both controls are enabled, Codex on this phone can inspect the foreground screen, take screenshots, tap, scroll, enter non-password text, and use Back, Home or Recents." to "Quando entrambi|both i controlli|controls sono abilitati,|enabled, Codex su questo telefono|phone può ispezionare|inspect la schermata|screen in primo~piano,|foreground, acquisire|take schermate,|screenshots, toccare,|tap, scorrere,|scroll, inserire|enter testo|text non~password|non-password e usare|use Indietro,|Back, Home o Recenti.|Recents.",
        "Screen structure and screenshots are sent to your signed-in Codex only when its phone tools are used. Password fields are never returned or filled." to "La struttura|structure dello schermo|screen e le schermate|screenshots vengono inviate|sent al tuo Codex autenticato|signed-in solo|only quando vengono usati|used i suoi strumenti|tools per il telefono.|phone. I campi|fields password non vengono mai restituiti|returned né compilati.|filled.",
        "Hear what happened." to "Ascolta|Hear cosa è successo.|happened.",
        "A different sound for results, questions, approvals and problems." to "Un suono|sound diverso|different per risultati,|results, domande,|questions, approvazioni|approvals e problemi.|problems.",
        "Generated/played counters cover notification and manual speech. Voice-mode connection time includes idle time. Playback does not prove audibility. Cancelled generation can still consume allowance; these are local activity counters, not billing totals." to "I contatori|counters di generazione/riproduzione|generation/playback coprono|cover la voce|speech delle notifiche|notifications e quella manuale.|manual. Il tempo|time di connessione|connection della modalità|mode vocale|voice include|includes il tempo|time inattivo.|idle. La riproduzione|Playback non dimostra|prove l’udibilità.|audibility. La generazione|Generation annullata|cancelled può ancora consumare|consume la quota;|allowance; questi sono contatori|counters locali|local di attività,|activity, non totali|totals di fatturazione.|billing.",
        "Speech uses media volume; quiet mode, notification mute and Do Not Disturb still silence it." to "La voce|Speech usa|uses il volume multimediale;|media; la modalità|mode silenziosa,|quiet, le notifiche|notifications mute|muted e Non~disturbare|Do~Not~Disturb la silenziano|silence comunque.|still.",
        "Remind me when I’m needed" to "Ricordami|Remind~me quando sono richiesto|needed",
        "Questions, approvals and problems only" to "Solo|Only domande,|questions, approvazioni|approvals e problemi|problems",
        "Up to three reminders: 5, 15, then 30 minutes apart. Dismiss, reply or resolve the request to stop. Later snoozes for 30 minutes." to "Fino~a tre|three promemoria:|reminders: a 5, 15, poi|then 30 minuti|minutes di distanza.|apart. Chiudi,|Dismiss, rispondi|reply o risolvi|resolve la richiesta|request per fermarli.|stop. Più~tardi|Later rimanda|snoozes di 30 minuti.|minutes.",
        "Answer this request in the terminal." to "Rispondi|Answer a questa richiesta|request nel terminale.|terminal.",
        "Switch back to its pairing to check delivery. Removing this copy will not cancel work already submitted." to "Torna|Switch~back alla sua associazione|pairing per verificare|check la consegna.|delivery. Rimuovere|Removing questa copia|copy non annullerà|cancel il lavoro|work già|already inviato.|submitted.",
        "Codex may already have received it. We’ll check its saved receipt first and keep the same message identity. A missing receipt cannot guarantee that retrying won’t duplicate work." to "Codex potrebbe averlo già|already ricevuto.|received. Verificheremo|We’ll~check prima|first la ricevuta|receipt salvata|saved e manterremo|keep la stessa|same identità|identity del messaggio.|message. Una ricevuta|receipt mancante|missing non può garantire|guarantee che riprovare|retrying non duplichi|duplicate il lavoro.|work.",
        "Start where the work lives." to "Inizia|Start dove vive|lives il lavoro.|work.",
        "Describe the task, context and what done looks like." to "Descrivi|Describe l’incarico,|task, il contesto|context e cosa significa|means completato.|done.",
        "You’ll see progress here and get a notification when the task needs you or finishes." to "Vedrai|You’ll~see qui i progressi|progress e riceverai|receive una notifica|notification quando l’incarico|task richiede|needs il tuo intervento|input o termina.|finishes.",
        "The queue is empty." to "La coda|queue è vuota.|empty.",
        "This hides the conversation from recent tasks, pauses queued messages, and stops its notifications. Its history stays on the Codex host." to "Questo nasconde|hides la conversazione|conversation dagli incarichi|tasks recenti,|recent, mette~in~pausa|pauses i messaggi|messages in~coda|queued e ferma|stops le sue notifiche.|notifications. La sua cronologia|history rimane|stays sul computer|host Codex.",
        "Codex may already have received this message. Retrying can send it twice. Check the conversation first." to "Codex potrebbe aver già|already ricevuto|received questo messaggio.|message. Riprovare|Retrying può inviarlo|send due~volte.|twice. Controlla|Check prima|first la conversazione.|conversation.",
        "Excerpt · full content remains on the workstation" to "Estratto|Excerpt · il contenuto|content completo|full rimane|remains sul computer|workstation",
        "Output excerpt · full content remains on the workstation" to "Estratto|Excerpt dell’output · il contenuto|content completo|full rimane|remains sul computer|workstation",
        "Changes apply when the next turn starts. Steering keeps the running turn's settings." to "Le modifiche|Changes si applicano|apply quando inizia|starts il turno|turn successivo.|next. Orientare|Steering mantiene|keeps le impostazioni|settings del turno|turn in~corso.|running.",
        "No work updates yet" to "Ancora|Yet nessun aggiornamento|update sul lavoro|work",
        "Full permissions for new tasks" to "Permessi|Permissions completi|full per i nuovi|new incarichi|tasks",
        "Full device access. Commands run without approval prompts." to "Accesso|Access completo|full al dispositivo.|device. I comandi|commands vengono eseguiti|executed senza richieste|requests di approvazione.|approval.",
        "Applies to future tasks in this environment." to "Si applica|applies ai futuri|future incarichi|tasks in questo ambiente.|environment.",
        "Coordinator check-ins" to "Aggiornamenti|Updates del coordinatore|coordinator",
        "Status checks only; agent messages are manual" to "Solo|Only controlli|checks dello stato;|status; i messaggi|messages agli agenti|agents sono manuali|manual",
        "Work updates" to "Aggiornamenti|Updates del lavoro|work",
        "Work record" to "Registro|Record delle attività|activities",
        "Blackout" to "Schermo|Screen nero|black",
        "Blackout · keep screen awake" to "Schermo|Screen nero|black · mantieni|keep lo schermo|screen acceso|awake",
        "Add environment" to "Aggiungi|Add ambiente|environment",
        "Environment" to "Ambiente|Environment",
        "Environment name" to "Nome|Name dell’ambiente|dell’environment",
        "Codex credits" to "Crediti|Credits Codex",
        "Credit data unavailable" to "Dati|Data sui crediti|credits non disponibili|available",
        "Loading conversation…" to "Caricamento|Loading della conversazione…|conversation…",
        "Loading conversations" to "Caricamento|Loading delle conversazioni|conversations",
        "Conversations unavailable" to "Conversazioni|Conversations non disponibili|available",
        "Loading image…" to "Caricamento|Loading dell’immagine…|dell’image…",
        "Image unavailable" to "Immagine|Image non disponibile|available",
        "Loading" to "Caricamento|Loading",
        "App settings" to "Impostazioni|Settings dell’app",
        "Voice setup" to "Impostazioni|Settings vocali|voice",
        "Open source conversation" to "Apri|Open la conversazione|conversation di origine|origin",
        "Open parent task" to "Apri|Open l’incarico|l’task principale|parent",
        "Clear session" to "Svuota|Clear la conversazione|conversation",
        "Edit draft" to "Modifica|Edit la bozza|draft",
        "Remove saved copy" to "Rimuovi|Remove la copia|copy salvata|saved",
        "Queue message" to "Metti|Put il messaggio|message in coda|queue",
        "Send answer" to "Invia|Send la risposta|answer",
        "No pending requests" to "Nessuna richiesta|request in attesa|waiting",
        "Automatic language cycling" to "Alternanza|Cycling automatica|automatic delle lingue|languages",
        "Reduced motion · cycling without animation" to "Movimento|Motion ridotto|reduced · alternanza|cycling senza animazione|animation",
        "How much Italian?" to "Quanto italiano?|Italian?",
        "Talk" to "Parla|Speak",
        "Listen" to "Ascolta|Listen",
        "Settings" to "Impostazioni|Settings",
        "Backend" to "Motore|Backend",
        "App updates" to "Aggiornamenti|Updates dell’app",
        "Check for updates" to "Cerca|Check aggiornamenti|updates",
        "Italian immersion" to "Immersione|Immersion in italiano|Italian",
        "NextComp is up to date" to "NextComp è aggiornato|updated",
        "Full access" to "Accesso|Access completo|full",
        "Full permissions" to "Permessi|Permissions completi|full",
        "English support" to "Supporto|Support in inglese|English",
        "Language motion" to "Transizioni|Transitions di lingua|language",
        "Speech volume" to "Volume della voce|speech",
        "Answers & notes" to "Risposte|Answers e note|notes",
        "New task" to "Nuova|New attività|task",
        "New session" to "Nuova|New sessione|session",
        "New conversation" to "Nuova|New conversazione|conversation",
        "All sessions" to "Tutte|All le sessioni|sessions",
        "Show sessions" to "Mostra|Show sessioni|sessions",
        "Find sessions" to "Cerca|Find sessioni|sessions",
        "Search sessions" to "Cerca|Search sessioni|sessions",
        "Search conversations" to "Cerca|Search conversazioni|conversations",
        "Open conversation" to "Apri|Open conversazione|conversation",
        "Earlier messages" to "Messaggi|Messages precedenti|earlier",
        "Load earlier messages" to "Carica|Load messaggi|messages precedenti|earlier",
        "Load earlier" to "Carica|Load precedenti|earlier",
        "Reload conversation" to "Ricarica|Reload la conversazione|conversation",
        "Retry history" to "Ricarica|Reload la cronologia|history",
        "Loading history…" to "Caricamento|Loading della cronologia…|history…",
        "Notification settings" to "Impostazioni|Settings delle notifiche|notifications",
        "Send a test notification" to "Invia|Send una notifica|notification di prova|test",
        "Queued messages" to "Messaggi|Messages in coda|queue",
        "Resume queue" to "Riprendi|Resume la coda|queue",
        "Send message" to "Invia|Send messaggio|message",
        "Stop & send" to "Ferma|Stop e invia|send",
        "Stop & Send" to "Ferma|Stop e invia|send",
        "Stop and send" to "Ferma|Stop e invia|send",
        "Clear saved speech" to "Svuota|Clear la voce|speech salvata|saved",
        "Clear all saved speech" to "Svuota|Clear tutte|all le letture|readings salvate|saved",
        "Hide controls" to "Nascondi|Hide controlli|controls",
        "Controls & usage" to "Controlli|Controls e utilizzo|usage",
        "Message coordinator" to "Scrivi|Write al coordinatore|coordinator",
        "Message Codex…" to "Scrivi|Write a Codex…",
        "Write a message" to "Scrivi|Write un messaggio|message",
        "Type a message" to "Scrivi|Type un messaggio|message",
        "Type your message" to "Scrivi|Type il tuo messaggio|message",
        "Plan mode" to "Modalità|Mode pianificazione|planning",
        "Build mode" to "Modalità|Mode esecuzione|execution",
        "Model, effort & mode" to "Modello,|Model, impegno|effort e modalità|mode",
        "Project folder" to "Cartella|Folder del progetto|project",
        "This phone" to "Questo telefono|phone",
        "Saved answer" to "Risposta|Answer salvata|saved",
        "Keep reply" to "Conserva|Keep risposta|reply",
        "Waiting for an answer" to "In attesa|waiting di una risposta|answer",
        "Queue" to "In coda|queue",
        "Queued" to "In coda|queued",
        "Listening" to "In ascolto|listening",
        "Speaking" to "Voce|Speech in corso|progress",
        "Sending" to "Invio|Sending in corso|progress",
        "Sending…" to "Invio|Sending in corso…|progress…",
        "Submitting…" to "Invio|Submitting in corso…|progress…",
        "Working" to "Al lavoro|work",
        "Paused" to "In pausa|pause",
        "Offline" to "Non connesso|connected",
        "Codex offline" to "Codex non connesso|connected",
        "Failed" to "Non riuscito|succeeded",
        "Later" to "Più|More tardi|later",
        "Check connection" to "Verifica|Check la connessione|connection",
        "Reconnect now" to "Riconnetti|Reconnect ora|now",
        "Retry now" to "Riprova|Retry ora|now",
        "Send now" to "Invia|Send ora|now",
        "Steer now" to "Orienta|Steer ora|now",
        "Install update" to "Installa|Install aggiornamento|update",
        "Checking update…" to "Ricerca|Checking aggiornamenti…|updates…",
        "Rename conversation" to "Rinomina|Rename conversazione|conversation",
        "Your answer" to "La tua risposta|answer",
        "Keep latest reply in notes" to "Conserva|Keep l’ultima|l’latest risposta|reply nelle note|notes",
        "Checking Losangelex…" to "Verifica|Checking di Losangelex…",
        "Connect Losangelex" to "Collega|Connect Losangelex",
        "Your NextComp host needs a Losangelex backend connection. Register its private endpoint and token file on the host, then reload." to "Il computer|host di NextComp richiede|needs una connessione|connection al motore|backend Losangelex. Registra|Register sul computer|host l’indirizzo|l’endpoint privato|private e il file del token, poi|then ricarica.|reload.",
        "Approval outcome is unconfirmed. Refresh its state before any further action." to "L’esito|L’outcome dell’approvazione|dell’approval non è confermato.|confirmed. Aggiorna|Refresh lo stato|state prima|before di qualsiasi|any altra|further azione.|action.",
        "Stale after" to "Inattivo|Stale dopo|after",
        "Change target" to "Cambia|Change destinazione|target",
        "Previous agents" to "Agenti|Agents precedenti|previous",
        "Next agents" to "Agenti|Agents successivi|next",
        "Check and retry this message?" to "Controllare|Check e riprovare|retry questo messaggio?|message?",
        "Check and retry" to "Controlla|Check e riprova|retry",
        "Pause recovery" to "Sospendi|Pause il recupero|recovery",
        "You asked" to "Hai chiesto|asked",
        "Earlier work" to "Lavoro|Work precedente|earlier",
        "Recent team work" to "Lavoro|Work recente|recent del gruppo|team",
        "Recent voice work" to "Lavoro|Work vocale|voice recente|recent",
        "Follow-up in the same conversation" to "Proseguimento|Follow-up nella stessa|same conversazione|conversation",
        "Collected automatically from your requests and the resulting work. Follow-ups stay linked to their conversation." to "Raccolto|Collected automaticamente|automatically dalle tue richieste|requests e dal lavoro|work svolto.|performed. I proseguimenti|follow-ups restano|stay collegati|linked alla loro conversazione.|conversation.",
        "A completed turn records what the agent returned; it does not prove delivery or satisfaction." to "Un turno|turn completato|completed registra|records ciò che l’agente|l’agent ha restituito;|returned; non dimostra|proves la consegna|delivery o la soddisfazione.|satisfaction.",
        "Your work will appear here automatically as you use NextComp. Earlier followed conversations are filled in gradually." to "Il tuo lavoro|work apparirà|will~appear qui|here automaticamente|automatically mentre|while usi|use NextComp. Le conversazioni|conversations precedenti|earlier seguite|followed vengono aggiunte|added gradualmente.|gradually.",
        "This agent reports to its parent task. Review its results here and open the parent to send guidance. Direct replies to this agent are unavailable." to "Questo agente|agent risponde|reports all’incarico|all’task principale.|parent. Esamina|Review qui|here i risultati|results e apri|open l’incarico|l’task principale|parent per inviare|send indicazioni.|guidance. Le risposte|replies dirette|direct a questo agente|agent non sono disponibili.|available.",
        "Tap the black screen to return. Voice keeps working; Home and Lock remain available." to "Tocca|Tap lo schermo|screen nero|black per tornare.|return. La voce|voice continua|continues a funzionare;|work; Home e il blocco|lock restano|remain disponibili.|available.",
        "Updating messages" to "Aggiornamento|Updating messaggi|messages",
        "Observed balance decrease" to "Riduzione|Decrease del saldo|balance osservata|observed",
        "Across this account; not lifetime or per-session spending" to "Per questo account; non è la spesa|spending totale|lifetime né per conversazione|session",
        "Balance increased · comparison restarted" to "Saldo|Balance aumentato|increased · confronto|comparison ricominciato|restarted",
        "Waiting for comparable balance observations" to "In attesa|waiting di rilevazioni|observations confrontabili|comparable del saldo|balance",
        "Provider reports a spending or account limit" to "Il servizio|provider segnala|reports un limite|limit di spesa|spending o dell’account",
        "Continuation status unavailable" to "Stato|Status della continuazione|continuation non disponibile|available",
        "Included allowance exhausted · existing-credit continuation available" to "Quota|Allowance inclusa|included esaurita|exhausted · continuazione|continuation con i crediti|credits disponibili|available",
        "Included allowance exhausted · credits present; continuation unverified" to "Quota|Allowance inclusa|included esaurita|exhausted · crediti|credits presenti;|present; continuazione|continuation non verificata|verified",
        "Included allowance exhausted · no available credits reported" to "Quota|Allowance inclusa|included esaurita|exhausted · nessun credito|credit disponibile|available segnalato|reported",
        "Included allowance remains" to "Quota|Allowance inclusa|included ancora|still disponibile|available",
        "Approve once" to "Approva|Approve una volta|time",
        "Reload backends" to "Ricarica|Reload i motori|backends",
        "Use Codex" to "Usa|Use Codex",
        "Close image" to "Chiudi|Close l’immagine|l’image",
        "New team task" to "Nuovo|New incarico|task al gruppo|team",
        "Task name" to "Nome|Name dell’incarico|dell’task",
        "What should the team do?" to "Che cosa deve fare|do il gruppo?|team?",
        "Retry saved task" to "Riprova|Retry l’incarico|l’task salvato|saved",
        "Retry saved answer" to "Riprova|Retry la risposta|answer salvata|saved",
        "Open request" to "Apri|Open la richiesta|request",
        "Waiting for fresh usage" to "In attesa|waiting dei dati|data di utilizzo|usage aggiornati|fresh",
        "Important" to "In evidenza|focus",
        "Unsave" to "Rimuovi|Remove dai salvati|saved",
        "Needs you" to "Serve|Needs il tuo intervento|input",
        "Your turn" to "Tocca|Turn a te",
        "Checking" to "Verifica|Checking in corso|progress",
        "Shown in conversation above" to "Mostrato|Shown nella conversazione|conversation qui|here sopra|above",
        "Connect Codex" to "Collega|Connect Codex",
        "Later · 30m" to "Più|More tardi|later · 30 min",
        "Connect Codex on this phone" to "Collega|Connect Codex su questo telefono|phone",
        "Disconnect this phone" to "Scollega|Disconnect questo telefono|phone",
        "Enable NextComp in Accessibility" to "Abilita|Enable NextComp nelle impostazioni|settings di accessibilità|accessibility",
        "Speak messages" to "Leggi|Read i messaggi|messages ad alta|loud voce|voice",
        "Talk to Codex" to "Parla|Talk con Codex",
        "Answer pending question" to "Rispondi|Answer alla domanda|question in attesa|waiting",
        "Retained passage · source outside loaded history" to "Passaggio|Passage conservato|retained · origine|source fuori|outside dalla cronologia|history caricata|loaded",
        "Spoken update ·" to "Aggiornamento|Update vocale|spoken ·",
        "Surrounding conversation" to "Contesto|Context della conversazione|conversation",
        "No important updates yet" to "Ancora|Still nessun aggiornamento|update in evidenza|focus",
        "Archive conversation?" to "Archiviare|Archive la conversazione?|conversation?",
        "Send this message again?" to "Inviare|Send di nuovo|again questo messaggio?|message?",
        "Retry anyway" to "Riprova|Retry comunque|anyway",
        "Edit message" to "Modifica|Edit il messaggio|message",
        "Next-turn controls" to "Controlli|Controls per il prossimo|next turno|turn",
        "Message the team" to "Scrivi|Write al gruppo|team",
        "Retry saved command" to "Riprova|Retry il comando|command salvato|saved",
        "Starting…" to "Avvio…|Starting…",
        "Check task status" to "Verifica|Check lo stato|status dell’attività|dell’task",
        "Start task" to "Avvia|Start l’attività|l’task",
        "Unfollow" to "Smetti|Stop di seguire|following",
        "Loading…" to "Caricamento…|Loading…",
        "Load earlier activity" to "Carica|Load attività|activity precedenti|earlier",
        "Steer this task…" to "Orienta|Steer questa attività…|task…",
        "▶ Finished" to "▶ Completato|Finished",
        "▶ Question" to "▶ Domanda|Question",
        "▶ Problem" to "▶ Problema|Problem",
        "Allow once" to "Consenti|Allow una volta|time",
        "Send answers" to "Invia|Send risposte|answers",
        "Retry saved turn" to "Riprova|Retry il messaggio|message salvato|saved",
        "Check turn" to "Verifica|Check turno|turn",
        "Retry needed" to "Riprova|Retry",
        "Running a command" to "Esecuzione|Running di un comando|command",
        "Reading a file" to "Lettura|Reading di un file",
        "Editing a file" to "Modifica|Editing di un file",
        "Running tests" to "Esecuzione|Running dei test|tests",
        "Ask anything" to "Chiedi|Ask qualsiasi|any cosa|thing",
        "Steer running turn" to "Orienta|Steer il turno|turn",
        "What would you like Codex to do?" to "Che cosa vuoi far fare|do a Codex?",
        "Original / Italiano" to "Originale|Original / Italiano|Italian",
        "No speech was captured. Check microphone privacy, then tap the microphone to record again." to "Non è stato registrato|recorded alcun parlato.|speech. Controlla|Check le impostazioni|settings di privacy del microfono,|microphone, poi|then tocca|tap il microfono|microphone per registrare|record di nuovo.|again.",
        "Start voice" to "Avvia|Start la voce|voice",
        "Start voice in this conversation" to "Parla|Speak in questa conversazione|conversation",
        "Stop and send recording" to "Ferma|Stop e invia|send la registrazione|recording",
        "Your message" to "Il tuo messaggio|message",
        "Codex message" to "Messaggio|Message di Codex",
        "Text or talk to coordinate your sessions." to "Scrivi|Write o parla|talk per coordinare|coordinate le tue sessioni.|sessions.",
        "Coordinator chat" to "Chat del coordinatore|coordinator",
        "Coordinator keyboard" to "Tastiera|Keyboard del coordinatore|coordinator",
        "Send coordinator message" to "Invia|Send al coordinatore|coordinator",
        "Starting microphone" to "Avvio|Starting del microfono|microphone",
        "Finishing recording" to "Chiusura|Finishing della registrazione|recording",
        "Preparing speech" to "Preparazione|Preparing della voce|speech",
        "Read full spoken passage" to "Leggi|Read il brano|passage completo|full",
        "Dismiss captions" to "Chiudi|Dismiss i sottotitoli|captions",
        "NextComp spoken update" to "Aggiornamento|Update vocale|spoken di NextComp",
        "NextComp voice is on" to "La voce|voice di NextComp è attiva|on",
        "Unlock to read" to "Sblocca|Unlock per leggere|read",
        "Voice mode" to "Modalità|Mode vocale|voice",
        "End voice" to "Termina|End la voce|voice",
        "Volume down: talk · Volume up: pause or replay" to "Volume giù:|down: parla|talk · Volume su:|up: pausa|pause o riascolto|replay",
        "Spoken message controls" to "Controlli|Controls dei messaggi|messages vocali|spoken",
        "NextComp speech paused" to "Voce|Speech di NextComp in pausa|pause",
        "Listening to NextComp" to "Ascolto|Listening di NextComp",
        "NextComp spoken updates" to "Aggiornamenti|Updates vocali|spoken di NextComp",
        "NextComp update" to "Aggiornamento|Update di NextComp",
        "Your place is saved" to "Il punto|place di ascolto|listening è salvato|saved",
        "Pause any time" to "Puoi mettere|put in pausa|pause in qualsiasi|any momento|time",
        "Saved for later" to "Salvato|Saved per dopo|later",
        "Waiting while other audio plays" to "In attesa|waiting che finisca|finishes l’altro|l’other audio",
        "Paused by you" to "Messo|Put in pausa|pause da te",
        "Ready when you are" to "Pronto|Ready quando|when vuoi|want",
        "Headphones disconnected" to "Cuffie|Headphones scollegate|disconnected",
        "Speech stalled · Tap Resume to retry" to "Voce|Speech bloccata|stalled · Tocca|Tap Riprendi|Resume per riprovare|retry",
        "Offline voice unavailable · Tap Resume to retry" to "Voce|Voice offline non disponibile|available · Tocca|Tap Riprendi|Resume per riprovare|retry",
        "Could not prepare speech · Tap Resume to retry" to "Preparazione|Preparation della voce|speech non riuscita|successful · Tocca|Tap Riprendi|Resume per riprovare|retry",
        "Profile changed · Your place is saved" to "Profilo|Profile cambiato|changed · Il punto|place di ascolto|listening è salvato|saved",
        "Waiting for sound to be enabled" to "In attesa|waiting dell’attivazione|dell’enabling dell’audio",
        "Reconnect to your workstation to load the full message" to "Riconnettiti|Reconnect alla workstation per caricare|load il messaggio|message completo|full",
        "Install an offline English voice to listen" to "Installa|Install una voce|voice inglese|English offline per ascoltare|listen",
        "Paused for other audio" to "In pausa|pause per un altro|other audio",
        "Waiting for other audio to finish" to "In attesa|waiting che finisca|finishes l’altro|l’other audio",
        "Playback interrupted · Tap Resume to retry" to "Riproduzione|Playback interrotta|interrupted · Tocca|Tap Riprendi|Resume per riprovare|retry",
        "Allow microphone access to use voice." to "Consenti|Allow l’accesso|l’access al microfono|microphone per usare|use la voce.|voice.",
        "Open NextComp to start voice mode." to "Apri|Open NextComp per avviare|start la modalità|mode vocale.|voice.",
        "Microphone access is needed for voice mode. Enable it in Android app settings." to "La modalità|mode vocale|voice richiede|requires il microfono.|microphone. Attivalo|Enable-it nelle impostazioni|settings Android dell’app.",
        "Microphone access was revoked. Enable it in Android app settings." to "L’accesso|L’access al microfono|microphone è stato revocato.|revoked. Attivalo|Enable-it nelle impostazioni|settings Android dell’app.",
        "Another app is using audio. Try again when it finishes." to "Un’altra|Un’other app sta usando|using l’audio. Riprova|Retry quando|when ha finito.|finished.",
        "Another app is using audio. Retry when it finishes." to "Un’altra|Un’other app sta usando|using l’audio. Riprova|Retry quando|when ha finito.|finished.",
        "Recording interrupted by another app. Please repeat your turn." to "Registrazione|Recording interrotta|interrupted da un’altra|un’other app. Ripeti|Repeat il messaggio.|message.",
        "That device is not paired. Pair it in NextComp first." to "Il dispositivo|device non è abbinato.|paired. Abbinalo|Pair-it prima|first in NextComp.",
        "Unable to play speech. Press volume up to replay." to "Impossibile|Unable riprodurre|play la voce.|speech. Premi|Press volume su|up per riascoltare.|replay.",
        "Microphone stopped." to "Microfono|Microphone arrestato.|stopped.",
        "The voice turn stopped." to "Il messaggio|message vocale|voice si è interrotto.|stopped.",
        "Codex updates & replies" to "Aggiornamenti|Updates e risposte|replies di Codex",
        "Updates you request from Codex, questions, and replies to your phone messages." to "Aggiornamenti|Updates richiesti|requested a Codex, domande|questions e risposte|replies ai messaggi|messages dal telefono.|phone.",
        "Still needs your attention" to "Richiede|Needs ancora|still la tua attenzione|attention",
        "Your reply…" to "La tua risposta…|reply…",
        "Open task" to "Apri|Open l’attività|l’task",
        "Sending reply to Codex…" to "Invio|Sending della risposta|reply a Codex…",
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
