package com.serviceflow.task;

import jakarta.validation.constraints.*;

public final class TaskCommands {
    private TaskCommands() {}

    public record Create(@NotNull @Min(0) Integer expectedTicketVersion,
                         @NotBlank @Size(max=240) String title) {}
    public record Assign(@NotNull @Min(0) Integer expectedTicketVersion,
                         @NotNull @Min(0) Integer expectedTaskVersion,
                         @NotNull @Positive Long assigneeId,
                         @NotBlank @Size(max=500) String reason) {}
    public record Reassign(@NotNull @Min(0) Integer expectedTicketVersion,
                           @NotNull @Min(0) Integer expectedTaskVersion,
                           @NotNull @Positive Long expectedAssigneeId,
                           @NotNull @Positive Long assigneeId,
                           @NotBlank @Size(max=500) String reason) {}
    public record Progress(@NotNull @Min(0) Integer expectedTicketVersion,
                           @NotNull @Min(0) Integer expectedTaskVersion) {}
    public record Complete(@NotNull @Min(0) Integer expectedTicketVersion,
                           @NotNull @Min(0) Integer expectedTaskVersion,
                           @NotBlank @Size(max=4000) String result) {}
    public record Cancel(@NotNull @Min(0) Integer expectedTicketVersion,
                         @NotNull @Min(0) Integer expectedTaskVersion,
                         @NotBlank @Size(max=500) String reason) {}
}
