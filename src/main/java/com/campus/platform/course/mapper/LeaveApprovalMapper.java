package com.campus.platform.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.platform.course.entity.LeaveApproval;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface LeaveApprovalMapper extends BaseMapper<LeaveApproval> {

    @Select("""
            SELECT * FROM leave_approval WHERE leave_id = #{leaveId} ORDER BY stage
            """)
    List<LeaveApproval> selectByLeave(@Param("leaveId") long leaveId);

    @Select("""
            <script>
            SELECT * FROM leave_approval WHERE leave_id IN
            <foreach collection="leaveIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY leave_id, stage
            </script>
            """)
    List<LeaveApproval> selectByLeaves(@Param("leaveIds") Collection<Long> leaveIds);
}
