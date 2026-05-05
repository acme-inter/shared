package com.acme.shared.payload.gmail;

import lombok.Data;

import java.util.List;

@Data
public class ModifyLabelsDTO {
  private List<String> addLabelIds;
  private List<String> removeLabelIds;
}
