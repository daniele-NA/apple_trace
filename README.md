# Apple Trace

<p align="center">
  <img src="ASSETS/apple-trace.svg" alt="Apple Trace logo" width="180" />
</p>

Bello vedere i log di iOS dalla console dell'IDE.

Ma se devi debuggare il tap su una notifica, un deep link, o qualsiasi apertura dell'app che non parte da Android Studio, quei log non li vedi.

Apple Trace risolve esattamente questo: un Logcat, ma per iOS.

- log live da simulatori iOS e device fisici
- ricerca veloce nel buffer visibile


# N.B: Funziona solo per simulatori

- Sui simulatori Apple c'è comunque la possibilità di testare praticamente tutte le features come notifiche,passkyes etc etc
- IOS non logga con il package , bensì : 2026-05-08 22:29:47.893589+0200 [D] Runner::: flutter: messaggio     , Quindi **per vedere correttamente i log e filtrarli** , usa un doppio pipe **||** all'inizio

<img src="ASSETS/preview.png">


## Installazione

Scarica lo `.zip` dalla sezione [Releases](https://github.com/daniele-NA/apple_trace/releases) di GitHub e importalo da **Settings → Plugins → Install Plugin from Disk...**
