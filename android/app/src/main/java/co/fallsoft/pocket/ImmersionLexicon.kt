package co.fallsoft.pocket

import java.util.Locale

/** Authored, offline learning vocabulary. Match whole labels; never rewrite executable syntax. */
object ImmersionLexicon {
    val common=mapOf(
        "English support" to "Supporto in inglese", "Message coordinator" to "Scrivi al coordinatore",
        "New task" to "Nuova attività", "Show sessions" to "Mostra sessioni", "Find sessions" to "Cerca sessioni",
        "Recent" to "Recenti", "Following" to "Seguiti", "Archived" to "Archiviati", "Allow once" to "Consenti una volta",
        "Decline" to "Rifiuta", "Send answers" to "Invia risposte", "Refresh" to "Aggiorna", "Load earlier messages" to "Carica messaggi precedenti",
        "Sending" to "Invio in corso", "Rename conversation" to "Rinomina conversazione", "Type your message" to "Scrivi il tuo messaggio",
        "Retry history" to "Ricarica la cronologia", "Retry saved turn" to "Riprova il messaggio salvato",
        "Work" to "Lavoro", "Mission" to "Missione", "Settings" to "Impostazioni", "Find" to "Cerca", "Talk" to "Parla",
        "Mic" to "Microfono", "Microphone" to "Microfono", "Keyboard" to "Tastiera", "Back" to "Indietro", "Stop" to "Ferma",
        "Send" to "Invia", "Send message" to "Invia messaggio", "Stop & send" to "Ferma e invia", "Stop & Send" to "Ferma e invia",
        "Record" to "Registra", "Recording" to "Registrazione", "Speaking" to "Voce in corso", "Listening" to "In ascolto",
        "Ready" to "Pronto", "Paused" to "In pausa", "Resume" to "Riprendi", "Pause" to "Pausa", "Replay" to "Riascolta",
        "Connected" to "Connesso", "Connecting" to "Connessione", "Reconnecting" to "Riconnessione", "Offline" to "Non connesso",
        "On" to "Attivo", "Off" to "Disattivo", "Notifications" to "Notifiche", "Notification" to "Notifica",
        "Queue" to "In coda", "Queued" to "In coda", "Steer" to "Orienta", "Reply" to "Rispondi", "Answer" to "Rispondi",
        "Skip" to "Salta", "Later" to "Più tardi", "Retry" to "Riprova", "Retry needed" to "Riprova", "Cancel" to "Annulla",
        "Save" to "Salva", "Close" to "Chiudi", "Done" to "Fatto", "Dismiss" to "Chiudi", "Copy" to "Copia",
        "Keep" to "Conserva", "Keep reply" to "Conserva risposta", "Rename" to "Rinomina", "Archive" to "Archivia", "Restore" to "Ripristina",
        "Model" to "Modello", "Effort" to "Impegno", "Mode" to "Modalità", "Follow" to "Segui", "New session" to "Nuova sessione",
        "New conversation" to "Nuova conversazione", "Coordinator" to "Coordinatore", "All sessions" to "Tutte le sessioni",
        "Working" to "Al lavoro", "Thinking" to "Riflessione", "Complete" to "Completato", "Completed" to "Completato", "Failed" to "Non riuscito",
        "Running a command" to "Esecuzione di un comando", "Reading a file" to "Lettura di un file", "Editing a file" to "Modifica di un file",
        "Searching" to "Ricerca", "Running tests" to "Esecuzione dei test", "Waiting for an answer" to "In attesa di una risposta",
        "Message" to "Messaggio", "Write a message" to "Scrivi un messaggio", "Ask anything" to "Chiedi qualsiasi cosa",
        "Type a message" to "Scrivi un messaggio", "Search conversations" to "Cerca conversazioni", "Search sessions" to "Cerca sessioni",
        "Volume" to "Volume", "Speech volume" to "Volume della voce", "Usage" to "Utilizzo", "Details" to "Dettagli",
        "Update" to "Aggiorna", "Updates" to "Aggiornamenti", "Check for updates" to "Cerca aggiornamenti", "Install update" to "Installa aggiornamento",
        "Full permissions" to "Permessi completi", "Workstation" to "Computer", "This phone" to "Questo telefono",
        "History" to "Cronologia", "Load earlier" to "Carica precedenti", "Open conversation" to "Apri conversazione",
        "Questions" to "Domande", "Attention" to "Attenzione", "Answers & notes" to "Risposte e note",
        "Steer running turn" to "Orienta il turno", "Project folder" to "Cartella del progetto",
        "What would you like Codex to do?" to "Che cosa vuoi far fare a Codex?",
        "Italian immersion" to "Immersione in italiano", "Original / Italiano" to "Originale / Italiano"
    )
    private val normalized=common.mapKeys{it.key.lowercase(Locale.ROOT)}
    fun italian(text:String):String?=normalized[text.trim().lowercase(Locale.ROOT)]
}
