package ru.transaction.service.client;

import lombok.AllArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * POST /actuator/dltreplay - переотправить накопившиеся в DLT сообщения в исходные топики.
 */
@Component
@Endpoint(id = "dltreplay")
@AllArgsConstructor
public class DltReplayEndpoint {

    private final DltReplayService dltReplayService;

    @WriteOperation
    public Map<String, Integer> replay() {
        return dltReplayService.replayAll();
    }
}
