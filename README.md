# bedrock-wire

## bedrock-wire 
Java knihovna pro spolehlivé HTTP volání a monitorování HTTP endpointů. 
Poskytuje sdílené connection pooly, správu TLS a konfigurovatelný monitoring s validací odpovědí.

## bedrock-wire-client
Reaktivní HTTP klient (Reactor/WebClient) se sdílenými connection pooly per transport target, správou TLS profilů včetně mTLS a centrálním HttpClientRegistry. 
Použitelný samostatně jako stavební blok pro jakýkoliv HTTP transport.

## bedrock-wire-monitor
Konfigurovatelný HTTP monitor postavený na bedrock-wire-client. 
Spouští HTTP checky v pravidelných intervalech, validuje odpovědi (HTTP status, obsah, regex, doba odezvy) a reportuje výsledky přes MonitorResultListener. 
Konfigurace přes properties namespace monitor.*.