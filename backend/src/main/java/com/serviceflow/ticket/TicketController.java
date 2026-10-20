package com.serviceflow.ticket;

import com.serviceflow.sla.SlaMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {
    private final TicketService service;
    public TicketController(TicketService service) { this.service = service; }

    @GetMapping
    public TicketService.Page list(@Valid @ModelAttribute TicketCommands.Query query) { return service.list(query); }

    @PostMapping("/{id}/cancel")
    public TicketService.Cancelled cancel(@PathVariable long id, @Valid @RequestBody TicketCommands.Cancel command,
                                          @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return service.cancel(id, command, key);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TicketService.Created create(@Valid @RequestBody TicketCommands.Create command,
                                         @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return service.create(command, key);
    }

    @GetMapping("/{id}")
    public TicketService.Details detail(@PathVariable long id) { return service.detail(id); }

    @GetMapping("/{id}/sla")
    public SlaMapper.Snapshot sla(@PathVariable long id) { return service.detail(id).sla(); }

    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketService.MessageAdded append(@PathVariable long id, @Valid @RequestBody TicketCommands.Message command,
                                              @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return service.append(id, command, key);
    }

    @PostMapping("/{id}/triage")
    public TicketService.Triaged triage(@PathVariable long id, @Valid @RequestBody TicketCommands.Triage command,
                                         @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return service.triage(id, command, key);
    }
}
