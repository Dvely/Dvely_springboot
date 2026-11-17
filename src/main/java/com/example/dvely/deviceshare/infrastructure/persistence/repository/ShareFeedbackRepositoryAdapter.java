package com.example.dvely.deviceshare.infrastructure.persistence.repository;

import com.example.dvely.deviceshare.domain.model.FeedbackImagePresence;
import com.example.dvely.deviceshare.domain.model.FeedbackImages;
import com.example.dvely.deviceshare.domain.model.ShareFeedback;
import com.example.dvely.deviceshare.domain.model.ShareFeedbackNote;
import com.example.dvely.deviceshare.domain.repository.ShareFeedbackRepository;
import com.example.dvely.deviceshare.domain.value.FeedbackStatus;
import com.example.dvely.deviceshare.infrastructure.persistence.entity.ShareFeedbackEntity;
import com.example.dvely.deviceshare.infrastructure.persistence.entity.ShareFeedbackImagesEntity;
import com.example.dvely.deviceshare.infrastructure.persistence.entity.ShareFeedbackMarkImageEntity;
import com.example.dvely.deviceshare.infrastructure.persistence.entity.ShareFeedbackNoteEntity;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class ShareFeedbackRepositoryAdapter implements ShareFeedbackRepository {

    private final SpringDataShareFeedbackRepository feedback;
    private final SpringDataShareFeedbackImagesRepository images;
    private final SpringDataShareFeedbackNoteRepository notes;
    private final SpringDataShareFeedbackMarkImageRepository markImages;

    @Override
    @Transactional
    public ShareFeedback save(ShareFeedback item, FeedbackImages pictures) {
        ShareFeedbackEntity saved = feedback.save(ShareFeedbackEntity.from(item, now()));
        if (pictures.hasCrop() || pictures.hasScreen() || pictures.hasAnnotated()) {
            images.save(new ShareFeedbackImagesEntity(saved.getId(),
                    pictures.hasCrop() ? pictures.crop() : null, pictures.hasScreen() ? pictures.screen() : null,
                    pictures.hasAnnotated() ? pictures.annotated() : null));
        }
        pictures.markCrops().forEach((markNo, crop) -> {
            if (crop != null && crop.length > 0) {
                markImages.save(new ShareFeedbackMarkImageEntity(saved.getId(), markNo, crop));
            }
        });
        return saved.toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShareFeedback> findById(Long feedbackId) {
        return feedback.findById(feedbackId).map(ShareFeedbackEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShareFeedback> findByShare(Long shareId, int limit) {
        return feedback.findByShare(shareId, PageRequest.of(0, limit)).stream().map(ShareFeedbackEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShareFeedback> findByShareAndStatus(Long shareId, FeedbackStatus status, int limit) {
        return feedback.findByShareAndStatus(shareId, status.name(), PageRequest.of(0, limit)).stream()
                .map(ShareFeedbackEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShareFeedback> findByUser(Long userId, Collection<FeedbackStatus> statuses, int limit) {
        if (statuses.isEmpty()) {
            return List.of();
        }
        return feedback.findByUser(userId, statuses.stream().map(Enum::name).toList(), PageRequest.of(0, limit))
                .stream().map(ShareFeedbackEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByShareSince(Long shareId, LocalDateTime since) {
        return feedback.countByShareSince(shareId, since);
    }

    @Override
    @Transactional
    public boolean moveStatus(Long feedbackId, Collection<FeedbackStatus> from, FeedbackStatus to, LocalDateTime now) {
        return feedback.moveStatus(feedbackId, from.stream().map(Enum::name).toList(), to.name(), now) > 0;
    }

    @Override
    @Transactional
    public void addNote(ShareFeedbackNote note) {
        notes.save(ShareFeedbackNoteEntity.from(note, now()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShareFeedbackNote> findNotes(Long feedbackId) {
        return notes.findByFeedback(feedbackId).stream().map(ShareFeedbackNoteEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public FeedbackImages findImages(Long feedbackId) {
        return images.findById(feedbackId)
                .map(row -> new FeedbackImages(row.getCrop(), row.getScreen(), row.getAnnotated(), Map.of()))
                .orElse(FeedbackImages.NONE);
    }

    @Override
    @Transactional(readOnly = true)
    public FeedbackImagePresence imagePresence(Long feedbackId) {
        List<Object[]> rows = feedback.imagePresence(feedbackId);
        List<Integer> marks = markImages.markNumbers(feedbackId);
        if (rows.isEmpty()) {
            return new FeedbackImagePresence(false, false, false, marks);
        }
        Object[] row = rows.getFirst();
        return new FeedbackImagePresence(truthy(row[0]), truthy(row[1]), truthy(row[2]), marks);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<byte[]> findMarkCrop(Long feedbackId, int markNo) {
        return markImages.findById(new ShareFeedbackMarkImageEntity.Key(feedbackId, markNo))
                .map(ShareFeedbackMarkImageEntity::getCrop);
    }

    @Override
    @Transactional
    public int deleteForLinksEndedBefore(LocalDateTime cutoff) {
        return feedback.deleteForLinksEndedBefore(cutoff);
    }

    private static boolean truthy(Object value) {
        return value instanceof Number n ? n.intValue() != 0 : Boolean.TRUE.equals(value);
    }

    /** DATETIME(6) 와 같은 정밀도로 맞춰 둬야 저장 전후 값이 같다. */
    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }
}
