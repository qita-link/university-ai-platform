package com.campus.platform.classroom.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.classroom.dto.RoomBookingCreateRequest;
import com.campus.platform.classroom.dto.RoomBookingDecisionRequest;
import com.campus.platform.classroom.dto.RoomBookingQuery;
import com.campus.platform.classroom.dto.RoomBookingView;
import com.campus.platform.classroom.entity.RoomBooking;
import com.campus.platform.classroom.entity.RoomBookingStatus;
import com.campus.platform.classroom.mapper.RoomBookingMapper;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.notification.entity.NotificationCategory;
import com.campus.platform.notification.entity.NotificationLevel;
import com.campus.platform.notification.service.NotificationService;
import com.campus.platform.scheduling.entity.SchRoom;
import com.campus.platform.scheduling.mapper.SchRoomMapper;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.setting.service.SettingService;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.mapper.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 教室借用：申请 → 审批 → 通知。
 * <p>
 * 冲突校验分两层：
 * <ol>
 *   <li>申请时用「空教室」同一套口径检查课表占用，并检查是否已有已批准的借用；</li>
 *   <li>审批时再查一次已批准借用，避免两个申请分别提交、同时被批到同一时段。</li>
 * </ol>
 */
@Service
public class RoomBookingService {

    static final String MODULE = "room";

    private final RoomBookingMapper bookingMapper;
    private final SchRoomMapper roomMapper;
    private final SysUserMapper userMapper;
    private final ClassroomService classroomService;
    private final SettingService settingService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final Clock clock;

    public RoomBookingService(RoomBookingMapper bookingMapper, SchRoomMapper roomMapper, SysUserMapper userMapper,
                              ClassroomService classroomService, SettingService settingService,
                              NotificationService notificationService, AuditService auditService, Clock clock) {
        this.bookingMapper = bookingMapper;
        this.roomMapper = roomMapper;
        this.userMapper = userMapper;
        this.classroomService = classroomService;
        this.settingService = settingService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public RoomBookingView create(AuthenticatedUser user, RoomBookingCreateRequest req) {
        Permissions.require(user, Permissions.ROOM_BOOK);
        SchRoom room = requireRoom(req.roomId());
        LocalDate today = LocalDate.now(clock);
        if (req.bookingDate().isBefore(today)) {
            throw BusinessException.badRequest("不能借用过去的日期");
        }
        int advance = intSetting("room.booking.advance.days", 14);
        if (req.bookingDate().isAfter(today.plusDays(advance))) {
            throw BusinessException.badRequest("最多提前 " + advance + " 天申请");
        }
        if (req.endPeriod() < req.startPeriod()) {
            throw BusinessException.badRequest("结束节次不能早于开始节次");
        }
        requireFree(req.roomId(), req.bookingDate(), req.startPeriod(), req.endPeriod(), 0L);

        RoomBooking booking = new RoomBooking();
        booking.setRoomId(room.getId());
        booking.setApplicantId(user.userId());
        booking.setBookingDate(req.bookingDate());
        booking.setStartPeriod(req.startPeriod());
        booking.setEndPeriod(req.endPeriod());
        booking.setPurpose(req.purpose().trim());
        booking.setAttendees(req.attendees());
        booking.setContact(req.contact() == null || req.contact().isBlank() ? null : req.contact().trim());
        booking.setStatus(RoomBookingStatus.PENDING);
        booking.setVersion(0);
        bookingMapper.insert(booking);

        List<Long> approvers = userMapper.selectActiveUserIdsByPermission(Permissions.ROOM_APPROVE);
        notificationService.notifyAll(approvers, NotificationCategory.ROOM, NotificationLevel.WARNING,
                "有新的教室借用申请",
                user.displayName() + " 申请借用 " + room.getName() + "（" + req.bookingDate()
                        + " 第 " + req.startPeriod() + "–" + req.endPeriod() + " 节）：" + booking.getPurpose(),
                "/services?tab=booking-review", "ROOM_BOOKING", booking.getId());
        audit("ROOM_BOOKING_SUBMIT", booking.getId(), "roomId", room.getId(), "date", req.bookingDate());
        return view(booking, user, Map.of(), Map.of());
    }

    public PageResult<RoomBookingView> mine(AuthenticatedUser user, RoomBookingQuery query) {
        Permissions.require(user, Permissions.ROOM_BOOK);
        LambdaQueryWrapper<RoomBooking> w = new LambdaQueryWrapper<RoomBooking>()
                .eq(RoomBooking::getApplicantId, user.userId());
        applyFilters(w, query);
        return page(user, query, w);
    }

    public PageResult<RoomBookingView> review(AuthenticatedUser user, RoomBookingQuery query) {
        Permissions.require(user, Permissions.ROOM_APPROVE);
        LambdaQueryWrapper<RoomBooking> w = new LambdaQueryWrapper<>();
        w.eq(RoomBooking::getStatus, query.getStatus() == null ? RoomBookingStatus.PENDING : query.getStatus());
        applyFilters(w, query);
        return page(user, query, w);
    }

    @Transactional
    public RoomBookingView decide(AuthenticatedUser user, long id, RoomBookingDecisionRequest req) {
        Permissions.require(user, Permissions.ROOM_APPROVE);
        RoomBooking booking = requireBooking(id);
        if (booking.getStatus() != RoomBookingStatus.PENDING) {
            throw BusinessException.conflict("该申请已处理");
        }
        boolean approve = Boolean.TRUE.equals(req.approve());
        String comment = req.comment() == null || req.comment().isBlank() ? null : req.comment().trim();
        if (approve) {
            requireFree(booking.getRoomId(), booking.getBookingDate(), booking.getStartPeriod(),
                    booking.getEndPeriod(), booking.getId());
        }
        if (bookingMapper.decide(id, approve ? "APPROVED" : "REJECTED", user.userId(), comment,
                LocalDateTime.now(clock)) != 1) {
            throw BusinessException.conflict("该申请已被其他人处理");
        }
        SchRoom room = roomMapper.selectById(booking.getRoomId());
        notificationService.notify(booking.getApplicantId(), NotificationCategory.ROOM,
                approve ? NotificationLevel.SUCCESS : NotificationLevel.WARNING,
                approve ? "教室借用已通过" : "教室借用被驳回",
                (room == null ? "教室" : room.getName()) + " · " + booking.getBookingDate()
                        + " 第 " + booking.getStartPeriod() + "–" + booking.getEndPeriod() + " 节"
                        + (comment == null ? "" : "：" + comment),
                "/services?tab=bookings", "ROOM_BOOKING", booking.getId());
        audit(approve ? "ROOM_BOOKING_APPROVE" : "ROOM_BOOKING_REJECT", id, "roomId", booking.getRoomId(),
                "comment", comment);
        return view(bookingMapper.selectById(id), user, Map.of(), Map.of());
    }

    @Transactional
    public RoomBookingView cancel(AuthenticatedUser user, long id) {
        Permissions.require(user, Permissions.ROOM_BOOK);
        RoomBooking booking = requireBooking(id);
        if (!booking.getApplicantId().equals(user.userId())) {
            throw BusinessException.forbidden("只能取消本人的借用申请");
        }
        if (bookingMapper.cancel(id, user.userId(), "申请人取消", LocalDateTime.now(clock)) != 1) {
            throw BusinessException.conflict("该申请已处理，无法取消");
        }
        audit("ROOM_BOOKING_CANCEL", id, "roomId", booking.getRoomId());
        return view(bookingMapper.selectById(id), user, Map.of(), Map.of());
    }

    /* ---------------- 内部 ---------------- */

    private void requireFree(long roomId, LocalDate date, int startPeriod, int endPeriod, long excludeId) {
        if (!classroomService.isRoomFree(roomId, date, startPeriod, endPeriod)) {
            throw BusinessException.conflict("该时段教室已有课或其他占用，请换一间或换节次");
        }
        List<RoomBooking> approved = bookingMapper.selectApprovedOverlap(roomId, date, startPeriod, endPeriod,
                excludeId);
        if (!approved.isEmpty()) {
            throw BusinessException.conflict("该时段已有批准的借用，请换一间或换节次");
        }
    }

    private SchRoom requireRoom(long roomId) {
        SchRoom room = roomMapper.selectById(roomId);
        if (room == null || !"ACTIVE".equals(room.getStatus())) {
            throw BusinessException.notFound("教室");
        }
        return room;
    }

    private RoomBooking requireBooking(long id) {
        RoomBooking booking = bookingMapper.selectById(id);
        if (booking == null) {
            throw BusinessException.notFound("借用申请");
        }
        return booking;
    }

    private void applyFilters(LambdaQueryWrapper<RoomBooking> w, RoomBookingQuery query) {
        if (query.getStatus() != null) {
            w.eq(RoomBooking::getStatus, query.getStatus());
        }
        if (query.getRoomId() != null) {
            w.eq(RoomBooking::getRoomId, query.getRoomId());
        }
        if (query.getFrom() != null) {
            w.ge(RoomBooking::getBookingDate, query.getFrom());
        }
        if (query.getTo() != null) {
            w.le(RoomBooking::getBookingDate, query.getTo());
        }
    }

    private PageResult<RoomBookingView> page(AuthenticatedUser user, RoomBookingQuery query,
                                             LambdaQueryWrapper<RoomBooking> w) {
        w.orderByDesc(RoomBooking::getId);
        Page<RoomBooking> pageRequest = query.toPage();
        IPage<RoomBooking> page = bookingMapper.selectPage(pageRequest, w);
        List<RoomBooking> records = page.getRecords();
        Set<Long> roomIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        records.forEach(b -> {
            roomIds.add(b.getRoomId());
            userIds.add(b.getApplicantId());
            if (b.getReviewerId() != null) {
                userIds.add(b.getReviewerId());
            }
        });
        Map<Long, SchRoom> rooms = new HashMap<>();
        if (!roomIds.isEmpty()) {
            roomMapper.selectBatchIds(roomIds).forEach(r -> rooms.put(r.getId(), r));
        }
        Map<Long, SysUser> users = new HashMap<>();
        if (!userIds.isEmpty()) {
            userMapper.selectBatchIds(userIds).forEach(u -> users.put(u.getId(), u));
        }
        List<RoomBookingView> items = records.stream().map(b -> view(b, user, rooms, users)).toList();
        return new PageResult<>(items, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    private RoomBookingView view(RoomBooking booking, AuthenticatedUser viewer, Map<Long, SchRoom> rooms,
                                 Map<Long, SysUser> users) {
        SchRoom room = rooms.get(booking.getRoomId());
        if (room == null) {
            room = roomMapper.selectById(booking.getRoomId());
        }
        SysUser applicant = users.get(booking.getApplicantId());
        if (applicant == null) {
            applicant = userMapper.selectById(booking.getApplicantId());
        }
        SysUser reviewer = booking.getReviewerId() == null ? null : users.get(booking.getReviewerId());
        if (booking.getReviewerId() != null && reviewer == null) {
            reviewer = userMapper.selectById(booking.getReviewerId());
        }
        boolean canCancel = booking.getStatus() == RoomBookingStatus.PENDING
                && booking.getApplicantId().equals(viewer.userId());
        boolean canReview = booking.getStatus() == RoomBookingStatus.PENDING
                && viewer.hasPermission(Permissions.ROOM_APPROVE);
        return new RoomBookingView(booking.getId(), booking.getRoomId(),
                room == null ? null : room.getCode(), room == null ? null : room.getName(),
                room == null ? null : room.getBuilding(), booking.getApplicantId(),
                applicant == null ? null : applicant.getDisplayName(), booking.getBookingDate(),
                booking.getStartPeriod(), booking.getEndPeriod(), booking.getPurpose(), booking.getAttendees(),
                booking.getContact(), booking.getStatus(), reviewer == null ? null : reviewer.getDisplayName(),
                booking.getReviewComment(), booking.getReviewedAt(), canCancel, canReview, booking.getCreatedAt());
    }

    private int intSetting(String key, int fallback) {
        try {
            return Integer.parseInt(settingService.get(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void audit(String action, Long targetId, Object... kv) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("ROOM_BOOKING", targetId);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.detail(String.valueOf(kv[i]), kv[i + 1]);
        }
        auditService.record(b.build());
    }
}
