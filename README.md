# bedrock-wire

Modulární projekt pro HTTP komunikaci a monitorování HTTP endpointů.
Obsahuje dvě knihovny: bedrock-wire-client (transportní vrstva) a bedrock-wire-monitor (monitorovací vrstva).
Podrobná specifikace je v souboru [bedrock-wire-spec.md](bedrock-wire-spec.md).

## bedrock-wire-client
Reaktivní HTTP klient (Reactor/WebClient) se sdílenými connection pooly per transport target, správou TLS profilů včetně mTLS a centrálním HttpClientRegistry. 
Použitelný samostatně jako stavební blok pro jakýkoliv HTTP transport.

## bedrock-wire-monitor
Konfigurovatelný HTTP monitor postavený na bedrock-wire-client. 
Spouští HTTP checky v pravidelných intervalech, validuje odpovědi (HTTP status, obsah, regex, doba odezvy) a reportuje výsledky přes MonitorResultListener. 
Konfigurace přes properties namespace monitor.*.