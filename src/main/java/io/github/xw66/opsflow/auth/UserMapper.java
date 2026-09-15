package io.github.xw66.opsflow.auth;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface UserMapper {
    @Select("""
            SELECT id, username, display_name, enabled FROM app_user
            WHERE (#{keyword}='' OR LOCATE(#{keyword},username)>0 OR LOCATE(#{keyword},display_name)>0)
                AND (#{enabled} IS NULL OR enabled=#{enabled})
                AND (#{role} IS NULL OR EXISTS (SELECT 1 FROM user_role ur JOIN role r ON r.id=ur.role_id WHERE ur.user_id=app_user.id AND r.code=#{role}))
            ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}
            """)
    List<AdminUserRow> adminUsers(String keyword, Boolean enabled, Role role, int offset, int limit);
    @Select("<script>SELECT ur.user_id AS userId, r.code FROM user_role ur JOIN role r ON r.id=ur.role_id WHERE ur.user_id IN <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<UserRoleRow> rolesForUsers(java.util.Collection<Long> ids);
    @Select("SELECT id, username, password_hash, display_name, enabled FROM app_user WHERE username = #{username}")
    UserAccount findByUsername(String username);

    @Select("SELECT id, username, password_hash, display_name, enabled FROM app_user WHERE id = #{id}")
    UserAccount findById(long id);

    @Insert("INSERT INTO app_user(username, password_hash, display_name) VALUES (#{username}, #{passwordHash}, #{displayName})")
    int insert(String username, String passwordHash, String displayName);

    @Select("SELECT r.code FROM role r JOIN user_role ur ON ur.role_id = r.id WHERE ur.user_id = #{userId} ORDER BY r.id")
    List<Role> roles(long userId);

    @Insert("INSERT INTO user_role(user_id, role_id) SELECT #{userId}, id FROM role WHERE code = #{role}")
    int addRole(long userId, Role role);

    @Delete("DELETE FROM user_role WHERE user_id = #{userId}")
    int deleteRoles(long userId);

    @Update("UPDATE app_user SET enabled = #{enabled}, updated_at = UTC_TIMESTAMP(6) WHERE id = #{id}")
    int updateEnabled(long id, boolean enabled);

    @Select("SELECT id FROM role WHERE code = 'ADMIN' FOR UPDATE")
    long lockAdminRole();

    @Select("SELECT COUNT(*) FROM user_role ur JOIN role r ON r.id = ur.role_id WHERE r.code = 'ADMIN'")
    int countAdmins();
    record AdminUserRow(long id, String username, String displayName, boolean enabled) { }
    record UserRoleRow(long userId, Role code) { }
}
