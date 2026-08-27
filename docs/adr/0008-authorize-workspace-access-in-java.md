# 在 Java 中授权工作区访问

Keycloak 负责认证全局用户身份，Java 拥有工作区成员关系并评估每次业务操作的权限。工作区角色不作为长期 Token Claim：Java 根据经过认证的成员关系解析用户选择的工作区，使用 PostgreSQL Row-level Security 限制租户数据范围，并且永远不把客户端提供的 `workspaceId` 当作授权依据。
