package dev.askmetric.server.query;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 在独立事务中保存查询审计，确保拒绝和失败也不会随业务事务回滚。 */
@Service
public class QueryAuditService {
    private final QueryAuditMapper mapper;

    public QueryAuditService(QueryAuditMapper mapper) {
        this.mapper = mapper;
    }

    /** 使用新事务保存一条查询审计记录。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(QueryAuditRecord record) {
        if (mapper.insert(record) != 1) {
            throw new IllegalStateException("查询审计记录保存失败");
        }
    }
}
