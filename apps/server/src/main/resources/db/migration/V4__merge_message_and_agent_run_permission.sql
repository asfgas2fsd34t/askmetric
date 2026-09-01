UPDATE workspace_membership
SET permissions = array_remove(permissions, 'CREATE_AGENT_RUN');

UPDATE workspace_policy
SET allowed_permissions = array_remove(allowed_permissions, 'CREATE_AGENT_RUN');
