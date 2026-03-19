package cz.syntea.bedrock.wire.monitor.service;

import cz.syntea.bedrock.wire.monitor.listener.MonitorResultListener;
import cz.syntea.bedrock.wire.monitor.model.MonitorExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AppService implements MonitorResultListener {
    @Override
    public void onResult(MonitorExecutionResult result) {
        log.info("Check '{}' → {} ({}ms, {} attempts)",
                result.getCheckName(),
                result.getStatus(),
                result.getExecutionDuration().toMillis(),
                result.getAttempts());
        log.info("{}", result);
    }
}
