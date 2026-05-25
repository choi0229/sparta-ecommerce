package org.teamsparta.productapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsparta.productapi.domain.category.entity.Category;
import org.teamsparta.productapi.domain.category.repository.CategoryRepository;
import org.teamsparta.productapi.domain.product.dto.request.ProductCreateRequest;
import org.teamsparta.productapi.domain.product.dto.request.ProductImageAddRequest;
import org.teamsparta.productapi.domain.product.dto.request.ProductImageCreateRequest;
import org.teamsparta.productapi.domain.product.dto.request.ProductVariantRequest;
import org.teamsparta.productapi.domain.product.entity.OutboxEvent;
import org.teamsparta.productapi.domain.product.entity.Product;
import org.teamsparta.productapi.domain.product.entity.ProductImage;
import org.teamsparta.productapi.domain.product.entity.ProductVariant;
import org.teamsparta.productapi.domain.product.event.ProductVariantPublisher;
import org.teamsparta.productapi.domain.product.dto.response.ProductSummaryResponse;
import org.teamsparta.productapi.domain.product.repository.OutboxEventRepository;
import org.teamsparta.productapi.domain.product.repository.ProductImageRepository;
import org.teamsparta.productapi.domain.product.repository.ProductQueryRepository;
import org.teamsparta.productapi.domain.product.repository.ProductRepository;
import org.teamsparta.productapi.domain.product.repository.ProductVariantRepository;
import org.teamsparta.productapi.domain.product.service.ProductService;
import org.teamsparta.productapi.global.enums.ImageType;
import org.teamsparta.productapi.global.enums.Status;
import org.teamsparta.productapi.global.exception.DomainException;
import org.teamsparta.productapi.global.exception.DomainExceptionCode;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
public class ProductServiceTest {

    @InjectMocks
    private ProductService productService;

    @Mock
    private ProductRepository productRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private ProductVariantRepository productVariantRepository;
    @Mock
    private ProductQueryRepository productQueryRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private ProductVariantPublisher productVariantPublisher;
    @Mock
    private ProductImageRepository productImageRepository;

    private List<ProductVariantRequest> variants;
    private List<ProductImageCreateRequest> images;

    @BeforeEach
    void setUp(){
        variants = List.of(
                new ProductVariantRequest("SKU-1", new BigDecimal("1000"), 100, Map.of("color", "black")),
                new ProductVariantRequest("SKU-2", new BigDecimal("2000"), 100, Map.of("color", "white"))
        );
        images = List.of(
                new ProductImageCreateRequest("img1", "url1", ImageType.THUMBNAIL, 0, true)
        );
    }

    @Test
    @DisplayName("상품 생성 성공 - outbox 저장 확인")
    void createProduct_Success()throws Exception{
        // given
        ProductCreateRequest request = new ProductCreateRequest("테스트상품", "브랜드", 1L, "설명", variants, images);

        Category category = Category.builder()
                .name("category").parent(null).status(Status.ACTIVE).sortOrder(1).build();
        ReflectionTestUtils.setField(category, "id", 1L);

        given(categoryRepository.findById(1L)).willReturn(Optional.of(category));
        given(productVariantRepository.existsBySku(any())).willReturn(false);

        given(productRepository.save(any(Product.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(objectMapper.writeValueAsString(any())).willReturn("{}");

        // when
        productService.createProduct(request);

        // then
        then(productRepository).should(times(1)).save(any(Product.class));

        ArgumentCaptor<List<ProductVariant>> variantCaptor = ArgumentCaptor.forClass(List.class);
        then(productVariantRepository).should(times(1)).saveAll(variantCaptor.capture());
        assertThat(variantCaptor.getValue()).hasSize(2);
        assertThat(variantCaptor.getValue())
                .extracting(ProductVariant::getSku)
                .containsExactlyInAnyOrder("SKU-1", "SKU-2");

        ArgumentCaptor<List<OutboxEvent>> outboxCaptor = ArgumentCaptor.forClass(List.class);
        then(outboxEventRepository).should(times(1)).saveAll(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue()).hasSize(4);
        List<OutboxEvent> capturedEvents = outboxCaptor.getValue();
        assertThat(capturedEvents)
                .extracting(OutboxEvent::getAggregateType)
                .containsOnly("Inventory", "ProductProjection");

        then(objectMapper).should(times(4)).writeValueAsString(any());
    }

    @Test
    @DisplayName("상품 생성 성공 - variant/image가 null이어도 저장")
    void createProduct_Success_null()throws Exception{
        // given
        ProductCreateRequest request = new ProductCreateRequest("테스트상품", "브랜드", 1L, "설명", null, null);

        Category category = Category.builder().name("category").parent(null).status(Status.ACTIVE).sortOrder(1).build();
        ReflectionTestUtils.setField(category, "id", 1L);

        given(categoryRepository.findById(1L)).willReturn(Optional.of(category));
        given(productRepository.save(any(Product.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        productService.createProduct(request);

        // then
        then(productRepository).should(times(1)).save(any(Product.class));
        then(productVariantRepository).should(never()).saveAll(any());
        then(outboxEventRepository).should(never()).saveAll(any());
    }

    @Test
    @DisplayName("상품 생성 실패 - 카테고리 없음")
    void createProduct_fail_categoryNotfound(){
        // given
        ProductCreateRequest request = new ProductCreateRequest("테스트 상품", "브랜드", 1L, "설명", variants, images);
        given(categoryRepository.findById(1L)).willReturn(Optional.empty());

        // when & then
        DomainException exception = assertThrows(DomainException.class,() ->
                productService.createProduct(request));
        assertThat(exception.getMessage()).isEqualTo(DomainExceptionCode.NOT_FOUND_CATEGORY.getMessage());

        then(productRepository).shouldHaveNoInteractions();
        then(productVariantRepository).should(never()).saveAll(any());
        then(outboxEventRepository).should(never()).saveAll(any());
    }

    @Test
    @DisplayName("상품 생성 실패 - 중복된 SKU 존재")
    void createProduct_fail_duplicateSku() {
        // given
        ProductCreateRequest request = new ProductCreateRequest("테스트상품", "브랜드", 1L, "설명", variants, images);
        given(categoryRepository.findById(any())).willReturn(Optional.of(Category.builder().build()));
        given(productVariantRepository.existsBySku(any())).willReturn(true);

        // when & then
        DomainException exception = assertThrows(DomainException.class, () ->
                productService.createProduct(request));
        assertThat(exception.getMessage()).isEqualTo(DomainExceptionCode.DUPLICATE_SKU.getMessage());
    }

    @Test
    @DisplayName("상품 생성 실패 - 이벤트 직렬화 실패")
    void createProduct_fail_eventSerialize()throws Exception {
        // given
        ProductCreateRequest request = new ProductCreateRequest("테스트상품", "브랜드", 1L, "설명", variants, images);
        Category category = Category.builder().name("category").status(Status.ACTIVE).sortOrder(1).build();
        ReflectionTestUtils.setField(category, "id", 1L);

        given(categoryRepository.findById(1L)).willReturn(Optional.of(category));
        given(productVariantRepository.existsBySku(any())).willReturn(false);
        given(productRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        given(objectMapper.writeValueAsString(any())).willThrow(new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR));

        // when
        DomainException exception = assertThrows(DomainException.class,() ->
                productService.createProduct(request));
        assertThat(exception.getMessage()).isEqualTo(DomainExceptionCode.EVENT_PUBLISH_ERROR.getMessage());

        then(outboxEventRepository).should(never()).saveAll(any());
    }

    @Test
    @DisplayName("이미지 추가 - 대표 지정 없이 추가 시 순서가 빠른 것이 자동 대표")
    void addImages_autoPrimary_success() {
        // given
        Product product = Product.builder().name("상품").build();
        given(productRepository.findById(any())).willReturn(Optional.of(product));

        List<ProductImageAddRequest> newImages = List.of(
                new ProductImageAddRequest("key1", "url1", ImageType.THUMBNAIL, 2, false),
                new ProductImageAddRequest("key2", "url2", ImageType.THUMBNAIL, 1, false)
        );

        // when
        productService.addImages(1L, newImages);

        // then
        ProductImage primaryImage = product.getProductImages().stream()
                .filter(ProductImage::getIsPrimary)
                .findFirst().orElseThrow();
        assertThat(primaryImage.getStorageKey()).isEqualTo("key2");
    }

    @Test
    @DisplayName("이미지 추가 - 대표 지정이 들어오면 기존 대표는 해제")
    void addImages_updatePrimary_success() {
        // given
        Product product = Product.builder().name("상품").build();
        ProductImage oldPrimary = ProductImage.builder()
                .product(product).storageKey("old").url("oldUrl")
                .type(ImageType.THUMBNAIL)
                .sortOrder(0)
                .isPrimary(true)
                .build();
        product.getProductImages().add(oldPrimary);

        given(productRepository.findById(any())).willReturn(Optional.of(product));

        List<ProductImageAddRequest> newImages = List.of(
                new ProductImageAddRequest("newKey", "newUrl", ImageType.THUMBNAIL, 1, true)
        );

        // when
        productService.addImages(1L, newImages);

        // then
        assertThat(oldPrimary.getIsPrimary()).isFalse();
        assertThat(product.getProductImages().stream()
                .filter(ProductImage::getIsPrimary).count()).isEqualTo(1);
        assertThat(product.getProductImages().stream()
                .filter(ProductImage::getIsPrimary).findFirst().orElseThrow().getStorageKey())
                .isEqualTo("newKey");
    }

    @Test
    @DisplayName("이미지 추가 실패 - 상품 없음")
    void addImages_fail_notFoundProduct() {
        // given
        given(productRepository.findById(any())).willReturn(Optional.empty());

        // when & then
        DomainException exception = assertThrows(DomainException.class, () ->
                productService.addImages(1L, List.of(new ProductImageAddRequest("k","u", ImageType.DETAIL, 0, false))));

        assertThat(exception.getMessage()).isEqualTo(DomainExceptionCode.NOT_FOUND_PRODUCT.getMessage());
    }

    // ── searchProducts N+1 방지 테스트 ──────────────────────────────────────

    @Test
    @DisplayName("searchProducts - productIds 기반 images batch 조회 후 대표 이미지 assembling")
    void searchProducts_loadsImagesInBatch_assemblesPrimaryImageUrl() {
        // given
        Category category = Category.builder()
                .name("전자제품").parent(null).status(Status.ACTIVE).sortOrder(0).build();
        ReflectionTestUtils.setField(category, "id", 10L);

        Product p1 = Product.builder().name("상품A").brandName("브랜드").category(category).status(Status.ACTIVE).build();
        ReflectionTestUtils.setField(p1, "id", 1L);

        Product p2 = Product.builder().name("상품B").brandName("브랜드").category(category).status(Status.ACTIVE).build();
        ReflectionTestUtils.setField(p2, "id", 2L);

        // p1에는 대표 이미지 있음, p2에는 이미지 없음
        ProductImage img = ProductImage.builder()
                .product(p1).storageKey("key1").url("https://cdn.example.com/img1.jpg")
                .type(ImageType.THUMBNAIL).sortOrder(0).isPrimary(true)
                .build();

        Page<Product> productPage = new PageImpl<>(List.of(p1, p2));
        given(productQueryRepository.searchProducts(any(), any(), any(), any(), any())).willReturn(productPage);
        given(productImageRepository.findByProductIdIn(anyList())).willReturn(List.of(img));

        // when
        Page<ProductSummaryResponse> result = productService.searchProducts(null, null, null, null, Pageable.unpaged());

        // then: images batch 조회가 단 1회 호출됨 (N+1 없음)
        then(productImageRepository).should(times(1)).findByProductIdIn(anyList());

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).primaryImageUrl()).isEqualTo("https://cdn.example.com/img1.jpg");
        assertThat(result.getContent().get(1).primaryImageUrl()).isNull();  // 이미지 없으면 null
    }

    @Test
    @DisplayName("searchProducts - 결과가 비어 있으면 productImageRepository를 호출하지 않는다")
    void searchProducts_emptyPage_doesNotCallImageRepository() {
        // given
        Page<Product> emptyPage = Page.empty();
        given(productQueryRepository.searchProducts(any(), any(), any(), any(), any())).willReturn(emptyPage);

        // when
        Page<ProductSummaryResponse> result = productService.searchProducts(null, null, null, null, Pageable.unpaged());

        // then
        then(productImageRepository).should(never()).findByProductIdIn(anyList());
        assertThat(result.getContent()).isEmpty();
    }

    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("상품 생성 실패 - DB unique constraint 위반 시 DomainException(DUPLICATE_SKU) 변환")
    void createProduct_fail_dbConstraintViolation_translatesToDuplicateSku() throws Exception {
        // given
        // 애플리케이션 레벨 existsBySku 체크는 통과(race condition 상황 재현)
        List<ProductVariantRequest> vars = List.of(
                new ProductVariantRequest("SKU-RACE", new BigDecimal("1000"), 10, null)
        );
        ProductCreateRequest request = new ProductCreateRequest("상품", "브랜드", 1L, null, vars, null);

        Category category = Category.builder()
                .name("카테고리").parent(null).status(Status.ACTIVE).sortOrder(0).build();
        ReflectionTestUtils.setField(category, "id", 1L);

        given(categoryRepository.findById(1L)).willReturn(Optional.of(category));
        given(productVariantRepository.existsBySku("SKU-RACE")).willReturn(false);  // app 체크 통과
        given(productRepository.save(any(Product.class))).willAnswer(inv -> inv.getArgument(0));
        given(objectMapper.writeValueAsString(any())).willReturn("{}");

        // flush() 시점에 DB unique constraint 위반 발생 (race condition 재현)
        willThrow(new DataIntegrityViolationException("uk_product_variant_sku"))
                .given(productVariantRepository).flush();

        // when & then
        DomainException exception = assertThrows(DomainException.class,
                () -> productService.createProduct(request));

        assertThat(exception.getMessage()).isEqualTo(DomainExceptionCode.DUPLICATE_SKU.getMessage());
        assertThat(exception.getCode()).isEqualTo("DUPLICATE_SKU");
        then(outboxEventRepository).should(never()).saveAll(any());
    }
}
