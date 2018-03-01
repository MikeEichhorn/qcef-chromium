// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include <stdint.h>

#include <set>
#include <vector>

#include "base/location.h"
#include "base/run_loop.h"
#include "base/single_thread_task_runner.h"
#include "base/threading/thread_task_runner_handle.h"
#include "cc/animation/animation_host.h"
#include "cc/layers/solid_color_layer.h"
#include "cc/layers/surface_layer.h"
#include "cc/layers/surface_layer_impl.h"
#include "cc/output/compositor_frame.h"
#include "cc/surfaces/sequence_surface_reference_factory.h"
#include "cc/surfaces/surface_info.h"
#include "cc/test/fake_impl_task_runner_provider.h"
#include "cc/test/fake_layer_tree_host.h"
#include "cc/test/fake_layer_tree_host_client.h"
#include "cc/test/fake_layer_tree_host_impl.h"
#include "cc/test/fake_output_surface.h"
#include "cc/test/layer_tree_test.h"
#include "cc/test/test_task_graph_runner.h"
#include "cc/trees/layer_tree_host.h"
#include "testing/gmock/include/gmock/gmock.h"
#include "testing/gtest/include/gtest/gtest.h"

namespace cc {
namespace {

using testing::_;
using testing::Eq;

static constexpr FrameSinkId kArbitraryFrameSinkId(1, 1);

class SurfaceLayerTest : public testing::Test {
 public:
  SurfaceLayerTest()
      : host_impl_(&task_runner_provider_, &task_graph_runner_) {}

 protected:
  void SetUp() override {
    animation_host_ = AnimationHost::CreateForTesting(ThreadInstance::MAIN);
    layer_tree_host_ = FakeLayerTreeHost::Create(
        &fake_client_, &task_graph_runner_, animation_host_.get());
    layer_tree_host_->SetViewportSize(gfx::Size(10, 10));
    host_impl_.CreatePendingTree();
  }

  void TearDown() override {
    if (layer_tree_host_) {
      layer_tree_host_->SetRootLayer(nullptr);
      layer_tree_host_ = nullptr;
    }
  }

  FakeLayerTreeHostClient fake_client_;
  FakeImplTaskRunnerProvider task_runner_provider_;
  TestTaskGraphRunner task_graph_runner_;
  std::unique_ptr<AnimationHost> animation_host_;
  std::unique_ptr<FakeLayerTreeHost> layer_tree_host_;
  FakeLayerTreeHostImpl host_impl_;
};

class MockSurfaceReferenceFactory : public SequenceSurfaceReferenceFactory {
 public:
  MockSurfaceReferenceFactory() {}

  // SequenceSurfaceReferenceFactory implementation.
  MOCK_CONST_METHOD1(SatisfySequence, void(const SurfaceSequence&));
  MOCK_CONST_METHOD2(RequireSequence,
                     void(const SurfaceId&, const SurfaceSequence&));

 protected:
  ~MockSurfaceReferenceFactory() override = default;

 private:
  DISALLOW_COPY_AND_ASSIGN(MockSurfaceReferenceFactory);
};

// Check that one surface can be referenced by multiple LayerTreeHosts, and
// each will create its own SurfaceSequence that's satisfied on destruction.
TEST_F(SurfaceLayerTest, MultipleFramesOneSurface) {
  const base::UnguessableToken kArbitraryToken =
      base::UnguessableToken::Create();
  const SurfaceInfo info(
      SurfaceId(kArbitraryFrameSinkId, LocalSurfaceId(1, kArbitraryToken)), 1.f,
      gfx::Size(1, 1));
  const SurfaceSequence expected_seq1(FrameSinkId(1, 1), 1u);
  const SurfaceSequence expected_seq2(FrameSinkId(2, 2), 1u);
  const SurfaceId expected_id(kArbitraryFrameSinkId,
                              LocalSurfaceId(1, kArbitraryToken));

  scoped_refptr<MockSurfaceReferenceFactory> ref_factory =
      new testing::StrictMock<MockSurfaceReferenceFactory>();

  // We are going to set up the SurfaceLayers and LayerTreeHosts. Each layer
  // will require a sequence and no sequence should be satisfied for now.
  EXPECT_CALL(*ref_factory, RequireSequence(Eq(expected_id), Eq(expected_seq1)))
      .Times(1);
  EXPECT_CALL(*ref_factory, RequireSequence(Eq(expected_id), Eq(expected_seq2)))
      .Times(1);
  EXPECT_CALL(*ref_factory, SatisfySequence(_)).Times(0);

  auto layer = SurfaceLayer::Create(ref_factory);
  layer->SetPrimarySurfaceInfo(info);
  layer_tree_host_->GetSurfaceSequenceGenerator()->set_frame_sink_id(
      FrameSinkId(1, 1));
  layer_tree_host_->SetRootLayer(layer);

  auto animation_host2 = AnimationHost::CreateForTesting(ThreadInstance::MAIN);
  std::unique_ptr<FakeLayerTreeHost> layer_tree_host2 =
      FakeLayerTreeHost::Create(&fake_client_, &task_graph_runner_,
                                animation_host2.get());
  auto layer2 = SurfaceLayer::Create(ref_factory);
  layer2->SetPrimarySurfaceInfo(info);
  layer_tree_host2->GetSurfaceSequenceGenerator()->set_frame_sink_id(
      FrameSinkId(2, 2));
  layer_tree_host2->SetRootLayer(layer2);

  testing::Mock::VerifyAndClearExpectations(ref_factory.get());

  // Destroy the second LayerTreeHost. The sequence generated by its
  // SurfaceLayer must be satisfied and no new sequences must be required.
  EXPECT_CALL(*ref_factory, SatisfySequence(Eq(expected_seq2))).Times(1);
  layer_tree_host2->SetRootLayer(nullptr);
  layer_tree_host2.reset();
  animation_host2 = nullptr;
  base::RunLoop().RunUntilIdle();
  testing::Mock::VerifyAndClearExpectations(ref_factory.get());

  // Destroy the first LayerTreeHost. The sequence generated by its
  // SurfaceLayer must be satisfied and no new sequences must be required.
  EXPECT_CALL(*ref_factory, SatisfySequence(expected_seq1)).Times(1);
  EXPECT_CALL(*ref_factory, RequireSequence(_, _)).Times(0);
  layer_tree_host_->SetRootLayer(nullptr);
  layer_tree_host_.reset();
  base::RunLoop().RunUntilIdle();
  testing::Mock::VerifyAndClearExpectations(ref_factory.get());
}

// This test verifies that the primary and fallback SurfaceInfo are pushed
// across from SurfaceLayer to SurfaceLayerImpl.
TEST_F(SurfaceLayerTest, SurfaceInfoPushProperties) {
  // We use a nice mock here because we are not really interested in calls to
  // MockSurfaceReferenceFactory and we don't want warnings printed.
  scoped_refptr<SurfaceReferenceFactory> ref_factory =
      new testing::NiceMock<MockSurfaceReferenceFactory>();

  scoped_refptr<SurfaceLayer> layer = SurfaceLayer::Create(ref_factory);
  layer_tree_host_->SetRootLayer(layer);
  SurfaceInfo primary_info(
      SurfaceId(kArbitraryFrameSinkId,
                LocalSurfaceId(1, base::UnguessableToken::Create())),
      1.f, gfx::Size(1, 1));
  layer->SetPrimarySurfaceInfo(primary_info);

  std::unique_ptr<SurfaceLayerImpl> layer_impl =
      SurfaceLayerImpl::Create(host_impl_.pending_tree(), layer->id());
  layer->PushPropertiesTo(layer_impl.get());

  // Verify tha the primary SurfaceInfo is pushed through and that there is
  // no valid fallback SurfaceInfo.
  EXPECT_EQ(primary_info, layer_impl->primary_surface_info());
  EXPECT_EQ(SurfaceInfo(), layer_impl->fallback_surface_info());

  SurfaceInfo fallback_info(
      SurfaceId(kArbitraryFrameSinkId,
                LocalSurfaceId(2, base::UnguessableToken::Create())),
      2.f, gfx::Size(10, 10));
  layer->SetFallbackSurfaceInfo(fallback_info);
  layer->PushPropertiesTo(layer_impl.get());

  // Verify that the primary SurfaceInfo stays the same and the new fallback
  // SurfaceInfo is pushed through.
  EXPECT_EQ(primary_info, layer_impl->primary_surface_info());
  EXPECT_EQ(fallback_info, layer_impl->fallback_surface_info());
}

// Check that SurfaceSequence is sent through swap promise.
class SurfaceLayerSwapPromise : public LayerTreeTest {
 public:
  SurfaceLayerSwapPromise()
      : commit_count_(0), sequence_was_satisfied_(false) {}

  void BeginTest() override {
    layer_tree_host()->GetSurfaceSequenceGenerator()->set_frame_sink_id(
        FrameSinkId(1, 1));
    ref_factory_ = new testing::StrictMock<MockSurfaceReferenceFactory>();

    // Create a SurfaceLayer but don't add it to the tree yet. No sequence
    // should be required / satisfied.
    EXPECT_CALL(*ref_factory_, SatisfySequence(_)).Times(0);
    EXPECT_CALL(*ref_factory_, RequireSequence(_, _)).Times(0);
    layer_ = SurfaceLayer::Create(ref_factory_);
    SurfaceInfo info(
        SurfaceId(kArbitraryFrameSinkId, LocalSurfaceId(1, kArbitraryToken)),
        1.f, gfx::Size(1, 1));
    layer_->SetPrimarySurfaceInfo(info);
    testing::Mock::VerifyAndClearExpectations(ref_factory_.get());

    // Add the layer to the tree. A sequence must be required.
    SurfaceSequence expected_seq(kArbitraryFrameSinkId, 1u);
    SurfaceId expected_id(kArbitraryFrameSinkId,
                          LocalSurfaceId(1, kArbitraryToken));
    EXPECT_CALL(*ref_factory_, SatisfySequence(_)).Times(0);
    EXPECT_CALL(*ref_factory_,
                RequireSequence(Eq(expected_id), Eq(expected_seq)))
        .Times(1);
    layer_tree_host()->SetRootLayer(layer_);
    testing::Mock::VerifyAndClearExpectations(ref_factory_.get());

    // By the end of the test, the required sequence must be satisfied and no
    // more sequence must be required.
    EXPECT_CALL(*ref_factory_, SatisfySequence(Eq(expected_seq))).Times(1);
    EXPECT_CALL(*ref_factory_, RequireSequence(_, _)).Times(0);

    gfx::Size bounds(100, 100);
    layer_tree_host()->SetViewportSize(bounds);

    blank_layer_ = SolidColorLayer::Create();
    blank_layer_->SetIsDrawable(true);
    blank_layer_->SetBounds(gfx::Size(10, 10));

    PostSetNeedsCommitToMainThread();
  }

  virtual void ChangeTree() = 0;

  void DidCommitAndDrawFrame() override {
    base::ThreadTaskRunnerHandle::Get()->PostTask(
        FROM_HERE, base::BindOnce(&SurfaceLayerSwapPromise::ChangeTree,
                                  base::Unretained(this)));
  }

  void AfterTest() override {}

 protected:
  int commit_count_;
  bool sequence_was_satisfied_;
  scoped_refptr<SurfaceLayer> layer_;
  scoped_refptr<Layer> blank_layer_;
  scoped_refptr<MockSurfaceReferenceFactory> ref_factory_;

  const base::UnguessableToken kArbitraryToken =
      base::UnguessableToken::Create();
};

// Check that SurfaceSequence is sent through swap promise.
class SurfaceLayerSwapPromiseWithDraw : public SurfaceLayerSwapPromise {
 public:
  void ChangeTree() override {
    ++commit_count_;
    switch (commit_count_) {
      case 1:
        // Remove SurfaceLayer from tree to cause SwapPromise to be created.
        layer_tree_host()->SetRootLayer(blank_layer_);
        break;
      case 2:
        EndTest();
        break;
      default:
        NOTREACHED();
        break;
    }
  }
};

SINGLE_AND_MULTI_THREAD_TEST_F(SurfaceLayerSwapPromiseWithDraw);

// Check that SurfaceSequence is sent through swap promise and resolved when
// swap fails.
class SurfaceLayerSwapPromiseWithoutDraw : public SurfaceLayerSwapPromise {
 public:
  SurfaceLayerSwapPromiseWithoutDraw() : SurfaceLayerSwapPromise() {}

  DrawResult PrepareToDrawOnThread(LayerTreeHostImpl* host_impl,
                                   LayerTreeHostImpl::FrameData* frame,
                                   DrawResult draw_result) override {
    return DRAW_ABORTED_MISSING_HIGH_RES_CONTENT;
  }

  void ChangeTree() override {
    ++commit_count_;
    switch (commit_count_) {
      case 1:
        // Remove SurfaceLayer from tree to cause SwapPromise to be created.
        layer_tree_host()->SetRootLayer(blank_layer_);
        break;
      case 2:
        layer_tree_host()->SetNeedsCommit();
        break;
      default:
        EndTest();
        break;
    }
  }
};

MULTI_THREAD_TEST_F(SurfaceLayerSwapPromiseWithoutDraw);

}  // namespace
}  // namespace cc