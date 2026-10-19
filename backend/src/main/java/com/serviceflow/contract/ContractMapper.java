package com.serviceflow.contract;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.time.Instant;

@Mapper
public interface ContractMapper {
    record Contract(long id, long customerId, String serviceTier, String status, Instant validFrom, Instant validUntil) {}
    record Route(long ownerTeamId, int routingVersion) {}
    record Policy(long id, int responseTargetMinutes, int resolutionTargetMinutes) {}

    @Select("SELECT id,customer_id,service_tier,status,valid_from,valid_until FROM service_contract WHERE id=#{id} AND customer_id=#{customerId}")
    Contract contract(long id, long customerId);

    @Select("SELECT COUNT(*) FROM contract_entitlement WHERE contract_id=#{contractId} AND service_type=#{serviceType} AND is_enabled=1")
    int entitlement(long contractId, String serviceType);

    @Select("SELECT status FROM customer WHERE id=#{id}")
    String customerStatus(long id);

    @Select("SELECT owner_team_id,routing_version FROM service_type_routing WHERE service_type=#{serviceType}")
    Route route(String serviceType);

    @Select("""
            SELECT p.id,p.response_target_minutes,p.resolution_target_minutes
            FROM sla_policy_binding b JOIN sla_policy_version p ON p.id=b.active_policy_version_id
            WHERE b.service_tier=#{tier} AND b.service_type=#{serviceType} AND b.priority=#{priority}
            """)
    Policy policy(String tier, String serviceType, String priority);
}
