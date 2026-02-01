# Java Design Patterns for Spring Boot

## Factory Pattern

DTO의 static factory method로 Entity 변환:

```java
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserResponse {
    private Long id;
    private String name;

    @Builder
    public UserResponse(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public static UserResponse from(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .build();
    }
}
```

## Builder Pattern

Lombok `@Builder`로 구현:

```java
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SearchCriteria {
    private String keyword;
    private UserStatus status;
    private Integer minAge;
    private Integer maxAge;
    private int page = 0;
    private int size = 20;

    @Builder
    public SearchCriteria(String keyword, UserStatus status,
                         Integer minAge, Integer maxAge,
                         int page, int size) {
        this.keyword = keyword;
        this.status = status;
        this.minAge = minAge;
        this.maxAge = maxAge;
        this.page = page;
        this.size = size;
    }
}

// 사용
SearchCriteria criteria = SearchCriteria.builder()
        .keyword("john")
        .status(UserStatus.ACTIVE)
        .size(50)
        .build();
```

## QueryDSL Pattern

동적 쿼리를 위한 QueryDSL 사용 (타입 안전):

```java
@Repository
@RequiredArgsConstructor
public class UserRepositoryCustomImpl implements UserRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    public Page<User> findByConditions(
            String name,
            UserStatus status,
            LocalDateTime createdAfter,
            Pageable pageable) {

        QUser user = QUser.user;
        BooleanBuilder builder = new BooleanBuilder();

        if (name != null) {
            builder.and(user.name.eq(name));
        }
        if (status != null) {
            builder.and(user.status.eq(status));
        }
        if (createdAfter != null) {
            builder.and(user.createdAt.goe(createdAfter));
        }

        List<User> content = queryFactory
                .selectFrom(user)
                .where(builder)
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(user.count())
                .from(user)
                .where(builder)
                .fetchOne();

        return new PageImpl<>(content, pageable, total != null ? total : 0L);
    }
}
```

QueryDSL 설정 (build.gradle.kts):
```kotlin
dependencies {
    implementation("com.querydsl:querydsl-jpa:5.0.0:jakarta")
    annotationProcessor("com.querydsl:querydsl-apt:5.0.0:jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
}
```

## Mapper Pattern

복잡한 변환 로직을 위한 별도 Mapper:

```java
@Component
public class UserMapper {

    public User toEntity(CreateUserRequest request) {
        return User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .age(request.getAge())
                .build();
    }

    public UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .age(user.getAge())
                .build();
    }

    public List<UserResponse> toResponseList(List<User> users) {
        return users.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }
}
```

간단한 경우 DTO 내 `toEntity()`, `from()` 메서드 선호.

## Partial Update Pattern (PATCH)

null 체크로 부분 업데이트:

```java
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UpdateUserRequest {
    private String name;
    private String email;
    private Integer age;

    @Builder
    public UpdateUserRequest(String name, String email, Integer age) {
        this.name = name;
        this.email = email;
        this.age = age;
    }

    public void applyTo(User user) {
        if (name != null) {
            user.updateName(name);
        }
        if (email != null) {
            user.updateEmail(email);
        }
        if (age != null) {
            user.updateAge(age);
        }
    }
}

// Service에서
@Transactional
public UserResponse update(Long id, UpdateUserRequest request) {
    User user = userRepository.findById(id)
            .orElseThrow(() -> new UserNotFoundException(id));
    request.applyTo(user);
    return UserResponse.from(user);
}
```

## API Response Wrapper

일관된 API 응답 형식:

```java
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiResponse<T> {
    private boolean success;
    private T data;
    private String message;

    @Builder
    public ApiResponse(boolean success, T data, String message) {
        this.success = success;
        this.data = data;
        this.message = message;
    }

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .build();
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .message(message)
                .build();
    }

    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .build();
    }
}
```

## Exception Hierarchy Pattern

계층적 예외 구조:

```java
// Base exception
public abstract class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    protected BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}

// Specific exceptions
public class UserNotFoundException extends BusinessException {
    public UserNotFoundException(Long id) {
        super(ErrorCode.USER_NOT_FOUND);
    }
}

public class DuplicateEmailException extends BusinessException {
    public DuplicateEmailException(String email) {
        super(ErrorCode.DUPLICATE_EMAIL);
    }
}

// Error codes enum
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "Email already exists"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid request");

    private final HttpStatus status;
    private final String message;
}
```

## Utility Class Pattern

Utility 클래스는 인스턴스화 방지:

```java
public final class DateUtils {

    private DateUtils() {
        throw new AssertionError("Utility class cannot be instantiated");
    }

    public static LocalDateTime startOfDay(LocalDate date) {
        return date.atStartOfDay();
    }

    public static LocalDateTime endOfDay(LocalDate date) {
        return date.atTime(LocalTime.MAX);
    }

    public static boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }
}
```