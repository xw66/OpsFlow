package io.github.xw66.opsflow.support;

import io.github.xw66.opsflow.support.SupportModels.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SupportMapper {
    @Select("SELECT id FROM support_group WHERE id=#{id} FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Long lockGroup(long id);
    @Select("SELECT * FROM support_group WHERE id=#{id}")
    Group group(long id);
    @Select("SELECT * FROM support_group ORDER BY id LIMIT #{limit} OFFSET #{offset}")
    List<Group> groups(int offset, int limit);
    @Select("SELECT * FROM support_group WHERE leader_id=#{leaderId} ORDER BY id LIMIT #{limit} OFFSET #{offset}")
    List<Group> leaderGroups(long leaderId, int offset, int limit);
    @Insert("INSERT INTO support_group(name,leader_id,enabled) VALUES(#{name},#{leaderId},#{enabled})")
    int insertGroup(GroupInput input);
    @Update("UPDATE support_group SET name=#{input.name},leader_id=#{input.leaderId},enabled=#{input.enabled},version=version+1 WHERE id=#{id} AND version=#{input.version}")
    int updateGroup(long id, GroupInput input);

    @Select("SELECT * FROM support_agent WHERE id=#{id}")
    Agent agent(long id);
    @Select("SELECT * FROM support_agent WHERE user_id=#{userId}")
    Agent agentForUser(long userId);
    @Select("SELECT * FROM support_agent WHERE group_id=#{groupId} ORDER BY id LIMIT #{limit} OFFSET #{offset}")
    List<Agent> agents(long groupId, int offset, int limit);
    @Select("""
            SELECT a.user_id,u.username,u.display_name,a.online,
                (a.enabled=TRUE AND u.enabled=TRUE AND g.enabled=TRUE AND EXISTS(
                    SELECT 1 FROM user_role ur JOIN role r ON r.id=ur.role_id
                    WHERE ur.user_id=a.user_id AND r.code='AGENT')) AS available,
                (SELECT COUNT(*) FROM ticket t WHERE t.assignee_id=a.user_id AND t.group_id=a.group_id
                    AND t.status IN ('ASSIGNED','PROCESSING','PENDING')) AS active_count,
                (SELECT COUNT(*) FROM ticket t WHERE t.assignee_id=a.user_id AND t.group_id=a.group_id
                    AND t.status='PROCESSING') AS processing_count
            FROM support_agent a JOIN app_user u ON u.id=a.user_id JOIN support_group g ON g.id=a.group_id
            WHERE a.group_id=#{groupId} ORDER BY a.id LIMIT #{limit} OFFSET #{offset}
            """)
    List<MemberLoad> workload(long groupId, int offset, int limit);
    @Insert("INSERT INTO support_agent(user_id,group_id,enabled) VALUES(#{userId},#{groupId},#{enabled})")
    int insertAgent(AgentInput input);
    @Update("UPDATE support_agent SET group_id=#{input.groupId},enabled=#{input.enabled},online=FALSE,version=version+1 WHERE id=#{id} AND user_id=#{input.userId} AND version=#{input.version}")
    int updateAgent(long id, AgentInput input);
    @Update("UPDATE support_agent SET online=#{online},version=version+1 WHERE user_id=#{userId} AND enabled=TRUE AND version=#{version}")
    int setOnline(long userId, boolean online, long version);

    @Select("SELECT * FROM ticket_category WHERE id=#{id}")
    Category category(long id);
    @Select("""
            SELECT c.* FROM ticket_category c JOIN support_group g ON g.id=c.group_id
            WHERE (#{includeDisabled}=TRUE OR (c.enabled=TRUE AND g.enabled=TRUE))
            ORDER BY c.id LIMIT #{limit} OFFSET #{offset}
            """)
    List<Category> categories(boolean includeDisabled, int offset, int limit);
    @Select("""
            SELECT p.priority,p.response_minutes,p.resolve_minutes FROM sla_policy p
                JOIN ticket_category c ON c.id=p.category_id JOIN support_group g ON g.id=c.group_id
            WHERE p.category_id=#{categoryId} AND p.enabled=TRUE AND c.enabled=TRUE AND g.enabled=TRUE
            ORDER BY FIELD(p.priority,'LOW','NORMAL','HIGH','URGENT')
            """)
    List<AvailablePriority> availablePriorities(long categoryId);
    @Insert("INSERT INTO ticket_category(code,name,group_id,enabled) VALUES(#{code},#{name},#{groupId},#{enabled})")
    int insertCategory(CategoryInput input);
    @Update("UPDATE ticket_category SET code=#{input.code},name=#{input.name},group_id=#{input.groupId},enabled=#{input.enabled},version=version+1 WHERE id=#{id} AND version=#{input.version}")
    int updateCategory(long id, CategoryInput input);

    @Select("SELECT * FROM sla_policy WHERE id=#{id}")
    Policy policy(long id);
    @Select("SELECT * FROM sla_policy ORDER BY id LIMIT #{limit} OFFSET #{offset}")
    List<Policy> policies(int offset, int limit);
    @Insert("""
            INSERT INTO sla_policy(category_id,priority,response_minutes,resolve_minutes,auto_escalate,enabled)
            VALUES(#{categoryId},#{priority},#{responseMinutes},#{resolveMinutes},#{autoEscalate},#{enabled})
            """)
    int insertPolicy(PolicyInput input);
    @Update("""
            UPDATE sla_policy SET category_id=#{input.categoryId},priority=#{input.priority},
            response_minutes=#{input.responseMinutes},resolve_minutes=#{input.resolveMinutes},
            auto_escalate=#{input.autoEscalate},enabled=#{input.enabled},version=version+1
            WHERE id=#{id} AND version=#{input.version}
            """)
    int updatePolicy(long id, PolicyInput input);

    @Select("SELECT LAST_INSERT_ID()")
    long insertedId();
}
