package io.github.xw66.opsflow.common;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditMapper {
    @Insert("""
            INSERT INTO audit_log(operator_id, action, resource_type, resource_id, before_json, after_json, remark)
            VALUES (#{operatorId}, #{action}, #{resourceType}, #{resourceId}, #{beforeJson}, #{afterJson}, #{remark})
            """)
    void insert(Long operatorId, String action, String resourceType, long resourceId,
            String beforeJson, String afterJson, String remark);
}
