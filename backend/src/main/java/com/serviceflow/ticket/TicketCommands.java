package com.serviceflow.ticket;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class TicketCommands {
    private TicketCommands() {}

    public record Create(@NotNull @Positive Long customerId, @NotNull @Positive Long contractId,
                         @NotBlank @Size(max=240) String title,
                         @NotBlank @Size(max=4000) String description,
                         @NotBlank @Size(max=64) String serviceType,
                         @NotBlank @Pattern(regexp="HIGH|MEDIUM|LOW") String impact,
                         @NotBlank @Pattern(regexp="HIGH|MEDIUM|LOW") String urgency,
                         @Size(max=80) String reportedProductCode, @Size(max=80) String reportedProductVersion) {}

    public record Message(@NotNull @Min(0) Integer expectedTicketVersion,
                          @NotBlank @Size(max=4000) String content, Boolean customerVisible,
                          @Size(max=80) String reportedProductCode, @Size(max=80) String reportedProductVersion) {}

    public record Cancel(@NotNull @Min(0) Integer expectedTicketVersion,
                         @NotBlank @Size(max=500) String reason) {}

    public record Query(@Min(1) @Max(1000000) Integer page,
                        @Min(1) @Max(100) Integer size,
                        @Pattern(regexp="SUBMITTED|TRIAGED|ASSIGNED|IN_PROGRESS|PENDING_ACCEPTANCE|CLOSED|CANCELLED") String status,
                        @Pattern(regexp="P1|P2|P3") String priority,
                        @Positive Long customerId, @Positive Long ownerTeamId,
                        @Size(max=120) String keyword) {
        public Query {
            page = page == null ? 1 : page;
            size = size == null ? 20 : size;
            keyword = keyword == null ? null : keyword.strip();
        }
        public long offset() { return (page.longValue()-1)*size; }
    }

    public record Fact(@NotBlank @Size(max=64) String field, @NotBlank @Size(max=1000) String value,
                       @NotBlank @Size(max=1000) String sourceQuote, @Positive Long sourceMessageId) {}
    public record Followup(@NotBlank @Size(max=64) String field, @NotBlank @Size(max=500) String question) {}
    public record Diagnostic(@NotBlank @Size(max=1500) String content,
                             @NotBlank @Pattern(regexp="HYPOTHESIS|EVIDENCE_BASED") String claimType,
                             @NotNull @Size(max=5) List<@Positive Long> knowledgeRefs) {}
    public record Triage(@NotNull @Min(0) Integer expectedTicketVersion, @Positive Long proposalId,
                         @NotBlank @Pattern(regexp="DATABASE_CONNECTION|NETWORK|APPLICATION|ACCOUNT_PERMISSION|CONFIGURATION|OTHER") String confirmedCategory,
                         @NotNull @Size(max=20) List<@Valid Fact> confirmedFacts,
                         @NotNull @Size(max=10) List<@Valid Followup> confirmedFollowups,
                         @NotNull @Size(max=8) List<@Valid Diagnostic> confirmedDiagnostics,
                         @Size(max=2000) String humanNotes) {}
}
