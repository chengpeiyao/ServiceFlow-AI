package com.serviceflow.task;

import jakarta.validation.constraints.*;

/**
 * TaskCommands 类用于封装任务操作请求参数的 DTO（数据传输对象）类。
 * 该类包含多个静态内部类，每个内部类对应一个任务操作命令，
 * 用于在任务管理系统中传递和验证请求参数。
 */
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
