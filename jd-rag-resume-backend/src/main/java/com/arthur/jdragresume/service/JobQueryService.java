package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.JobListItem;
import com.arthur.jdragresume.common.LikeKeywords;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.mapper.JobDescriptionMapper;
import com.arthur.jdragresume.security.CurrentUserService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class JobQueryService {

    /**
     * 深分页上限：offset（= page * size）超过这个值直接拒绝，返回 400 PAGE_OUT_OF_RANGE。
     * <p>
     * 这样做有两个目的：
     * 1. 不再用 int 计算 page * size——超大页码会溢出成负数（MySQL 报语法错 → 500），
     *    更糟的是可能回绕成一个小的正数，悄悄返回错误那一页的数据；
     * 2. 一次拦住深分页，避免 LIMIT 巨大 offset 让数据库白扫大量行。
     * <p>
     * 在上限以内、但超过实际数据量的页码仍然合法，返回空列表。
     */
    static final long MAX_OFFSET = 10_000L;

    /**
     * LIKE 的转义字符，必须与 JobDescriptionMapper.xml 里的 {@code ESCAPE '!'} 保持一致。
     * <p>
     * 不用反斜杠：MySQL 开启 NO_BACKSLASH_ESCAPES 时 {@code '\\'} 的含义会变，'!' 不受 sql_mode 影响。
     */
    static final char LIKE_ESCAPE = LikeKeywords.LIKE_ESCAPE;

    private final JobDescriptionMapper jobDescriptionMapper;
    private final CurrentUserService currentUserService;

    public JobQueryService(JobDescriptionMapper jobDescriptionMapper,
                           CurrentUserService currentUserService) {
        this.jobDescriptionMapper = jobDescriptionMapper;
        this.currentUserService = currentUserService;
    }

    public List<JobListItem> search(String keyword, String location, int page, int size) {
        long offset = toOffset(page, size);
        AppUser user = currentUserService.getCurrentUser();
        return jobDescriptionMapper.searchJobs(
                user.getId(), escapeLikeKeyword(keyword), normalizeLocation(location), offset, size);
    }

    /**
     * 用户输入的关键字按字面匹配：先 trim，空白视为不过滤；再把 LIKE 通配符 % 和 _
     * 以及转义字符本身转义掉。否则搜 "%" 会返回全部岗位，搜 "C_" 会匹配到 "CA"。
     */
    static String escapeLikeKeyword(String keyword) {
        return LikeKeywords.escapeLikeKeyword(keyword);
    }

    /** location 是等值匹配，不涉及通配符，只做 trim；空白视为不过滤。 */
    static String normalizeLocation(String location) {
        if (location == null) {
            return null;
        }
        String trimmed = location.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static long toOffset(int page, int size) {
        if (page < 0 || size < 1) {
            throw new BusinessException("PAGE_OUT_OF_RANGE", "page 必须 >= 0，size 必须 >= 1");
        }
        // 先提升为 long 再乘：int 最大值 * 50 也远小于 long 上限，不会溢出。
        long offset = (long) page * size;
        if (offset > MAX_OFFSET) {
            throw new BusinessException(
                    "PAGE_OUT_OF_RANGE",
                    "页码过大：page * size 不能超过 " + MAX_OFFSET + "，请缩小筛选条件后再翻页"
            );
        }
        return offset;
    }
}
