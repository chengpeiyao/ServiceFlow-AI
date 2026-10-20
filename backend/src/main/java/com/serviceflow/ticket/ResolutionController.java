package com.serviceflow.ticket;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ResolutionController {
    private final ResolutionService service;
    public ResolutionController(ResolutionService service) { this.service = service; }

    @PostMapping("/tickets/{ticketId}/resolutions")
    @ResponseStatus(HttpStatus.CREATED)
    public ResolutionService.Submitted submit(@PathVariable long ticketId, @Valid @RequestBody ResolutionCommands.Submit command,
                                             @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return service.submit(ticketId, command, key);
    }

    @PostMapping("/resolutions/{id}/acceptance")
    public ResolutionService.Decided decide(@PathVariable long id, @Valid @RequestBody ResolutionCommands.Decide command,
                                           @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return service.decide(id, command, key);
    }

    @GetMapping("/tickets/{ticketId}/resolutions")
    public List<ResolutionMapper.History> history(@PathVariable long ticketId) { return service.history(ticketId); }
}
