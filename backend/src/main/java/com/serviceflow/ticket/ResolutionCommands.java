package com.serviceflow.ticket;

import jakarta.validation.constraints.*;

public final class ResolutionCommands {
    private ResolutionCommands() {}
    public record Submit(@NotNull @Min(0) Integer expectedTicketVersion,
                         @NotNull @Positive Long taskId,
                         @NotNull @Min(0) Integer expectedTaskVersion,
                         @NotBlank @Size(max=8000) String summary) {}
    public record Decide(@NotNull @Min(0) Integer expectedTicketVersion,
                         @NotNull @Pattern(regexp="ACCEPT|REJECT") String decision,
                         @Size(max=1000) String reason) {}
}
