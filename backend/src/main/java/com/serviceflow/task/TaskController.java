package com.serviceflow.task;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class TaskController {
    private final TaskService service;
    public TaskController(TaskService service) { this.service = service; }

    @PostMapping("/tickets/{ticketId}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskService.Changed create(@PathVariable long ticketId, @Valid @RequestBody TaskCommands.Create command,
                                      @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return service.create(ticketId, command, key);
    }

    @GetMapping("/tasks/{id}")
    public TaskService.Details detail(@PathVariable long id) { return service.detail(id); }

    @PostMapping("/tasks/{id}/assignments")
    public TaskService.Changed assign(@PathVariable long id, @Valid @RequestBody TaskCommands.Assign command,
                                      @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return service.assign(id, command, key);
    }

    @PostMapping("/tasks/{id}/reassignments")
    public TaskService.Changed reassign(@PathVariable long id, @Valid @RequestBody TaskCommands.Reassign command,
                                        @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return service.reassign(id, command, key);
    }

    @PostMapping("/tasks/{id}/accept")
    public TaskService.Changed accept(@PathVariable long id, @Valid @RequestBody TaskCommands.Progress command,
                                      @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return service.accept(id, command, key);
    }

    @PostMapping("/tasks/{id}/start")
    public TaskService.Changed start(@PathVariable long id, @Valid @RequestBody TaskCommands.Progress command,
                                     @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return service.start(id, command, key);
    }

    @PostMapping("/tasks/{id}/complete")
    public TaskService.Changed complete(@PathVariable long id, @Valid @RequestBody TaskCommands.Complete command,
                                        @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return service.complete(id, command, key);
    }

    @PostMapping("/tasks/{id}/cancel")
    public TaskService.Changed cancel(@PathVariable long id, @Valid @RequestBody TaskCommands.Cancel command,
                                      @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return service.cancel(id, command, key);
    }
}
